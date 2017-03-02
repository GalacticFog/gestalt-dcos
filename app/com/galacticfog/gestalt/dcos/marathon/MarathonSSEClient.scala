package com.galacticfog.gestalt.dcos.marathon

import javax.inject.{Inject, Singleton}

import scala.language.postfixOps
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.galacticfog.gestalt.dcos.{LauncherConfig, ServiceInfo}
import play.api.libs.ws.WSClient
import play.api.Logger
import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import de.heikoseeberger.akkasse.{EventStreamUnmarshalling, ServerSentEvent}
import akka.http.scaladsl.client.RequestBuilding.Get
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.ServiceStatus._
import de.heikoseeberger.akkasse.MediaTypes.`text/event-stream`

object BiggerUnmarshalling extends EventStreamUnmarshalling {
  override protected def maxLineSize: Int = 524288
  override protected def maxEventSize: Int = 524288
}

@Singleton
class MarathonSSEClient @Inject() (launcherConfig: LauncherConfig,
                                   ws: WSClient )
                                  ( implicit system: ActorSystem ) {

  import system.dispatcher
  import MarathonSSEClient._
  import BiggerUnmarshalling._

  val logger: Logger = Logger(this.getClass())

  private[this] val marathonBaseUrl = launcherConfig.marathon.baseUrl

  private[this] val appGroup = launcherConfig.marathon.appGroup
  private[this] val appIdWithGroup = s"/${appGroup}/(.*)".r

  private[this] val STATUS_UPDATE_TIMEOUT = 15.seconds

  def connectToBus(actorRef: ActorRef): Unit = {
    implicit val mat = ActorMaterializer()
    val handler = Sink.actorRef(actorRef, akka.actor.Status.Failure(new RuntimeException("stream closed")))
    Http(system)
      .singleRequest(
        Get(s"${marathonBaseUrl}/v2/events")
          .addHeader(
            Accept(`text/event-stream`)
          )
      )
      .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
      .onComplete {
        case Success(eventSource) =>
          logger.info("successfully attached to marathon event bus")
          actorRef ! Connected
          eventSource.runWith(handler)
        case Failure(t) =>
          logger.info("failure connecting to marathon event bus", t)
          actorRef ! akka.actor.Status.Failure(new RuntimeException("error connecting to Marathon event bus", t))
      }
  }

  def launchApp(appPayload: MarathonAppPayload): Future[JsValue] = {
    val appId = appPayload.id.stripPrefix("/")
    ws.url(s"${marathonBaseUrl}/v2/apps/${appId}")
      .withQueryString("force" -> "true")
      .put(
        Json.toJson(appPayload)
      ).flatMap { resp =>
      resp.status match {
        case 201 => Future.successful(resp.json)
        case 200 => Future.successful(resp.json)
        case _ =>
          logger.info(s"launchApp(${appId}) response: ${resp.status}:${resp.statusText}")
          Future.failed(new RuntimeException(
            Try{(resp.json \ "message").as[String]} getOrElse resp.body
          ))
      }
    }
  }

  def killApp(service: FrameworkService): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${service.name}")
    val appId = s"/${appGroup}/${service.name}"
    ws.url(s"${marathonBaseUrl}/v2/apps${appId}")
      .withQueryString("force" -> "true")
      .delete()
      .flatMap { resp =>
        logger.info(s"marathon.delete(${service.name}) => ${resp.statusText}")
        resp.status match {
          case 200 => Future.successful(true)
          case 404 => Future.successful(false)
          case _   => Future.failed(new RuntimeException(s"marathon.delete(${appId}) failed with ${resp.status}/${resp.body}"))
        }
      }
  }

  def stopApp(svcName: String): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${svcName}")
    ws.url(s"${marathonBaseUrl}/v2/apps/${appGroup}/${svcName}")
      .withQueryString("force" -> "true")
      .put(Json.obj("instances" -> 0))
      .map { resp =>
        logger.info(s"marathon.stop(${svcName}) => ${resp.statusText}")
        resp.status == 200
      }
  }

  private[marathon] def toServiceInfo(service: FrameworkService, app: MarathonAppPayload): ServiceInfo = {
    val staged  = app.tasksStaged.get
    val running = app.tasksRunning.get
    val healthy = app.tasksHealthy.get
    val sickly  = app.tasksUnhealthy.get
    val target  = app.instances

    val status = if (staged != 0) STAGING
    else if (target != running) WAITING
    else if (target == 0) STOPPED
    else if (sickly > 0) UNHEALTHY
    else if (target == healthy) HEALTHY
    else RUNNING

    val lbExposed = app.labels.filterKeys(_.matches("HAPROXY_GROUP")).nonEmpty

    val HAPROXY_N_ENABLED = "HAPROXY_([0-9]+)_ENABLED".r
    val lbDisabledPortIndices = app.labels.collect({
      case (HAPROXY_N_ENABLED(portNumber),v) if v == "false" => portNumber.toInt
    }).toSet

    val serviceEndpoints = launcherConfig.marathon.marathonLbUrl match {
      case Some(lbUrl) if lbExposed =>
        app.portDefinitions
          .getOrElse(Seq.empty)
          .map(_.port)
          .zipWithIndex
          .collect({
            case (portNumber, index) if !lbDisabledPortIndices.contains(index) => portNumber
          })
          .map(lbUrl + ":" + _)
      case _ => Seq.empty
    }

    val vhosts = getVHosts(app)

    val hostname = app.tasks.flatMap(_.headOption).flatMap(_.host)
    val ports = app.tasks.flatMap(_.headOption).flatMap(_.ports).map(_.map(_.toString)) getOrElse Seq.empty

    ServiceInfo(service,vhosts ++ serviceEndpoints,hostname,ports,status)
  }

  def getServices(): Future[Seq[ServiceInfo]] = {
    val url = marathonBaseUrl.stripSuffix("/")
    ws.url(s"${url}/v2/groups/${appGroup}").withQueryString("embed" -> "group.apps", "embed" -> "group.apps.counts", "embed" -> "group.apps.tasks").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get().flatMap { response =>
      response.status match {
        case 200 =>
          Future.fromTry(Try {
            (response.json \ "apps").as[Seq[MarathonAppPayload]].flatMap {
              app => appIdWithGroup.unapplySeq(app.id) flatMap(_.headOption) flatMap(LauncherConfig.Services.fromName) map (service => (service,app))
            } map { case (service,app) => toServiceInfo(service,app) }
          })
        case 404 => Future.successful(Seq.empty)
        case _ => Future.failed(new RuntimeException(response.statusText))
      }
    }
  }

  def getServiceStatus(service: FrameworkService): Future[ServiceInfo] = {
    val url = marathonBaseUrl.stripSuffix("/")
    ws.url(s"${url}/v2/apps/${appGroup}/${service.name}").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get().flatMap { response =>
      response.status match {
        case 200 =>
          Future.fromTry(Try{
            val app = (response.json \ "app").as[MarathonAppPayload]
            toServiceInfo(service, app)
          })
        case 404 => Future.successful(ServiceInfo(service,Seq(),None,Seq.empty,NOT_FOUND))
        case _   =>
          Future.failed(new RuntimeException(response.statusText))
      }
    } recover {
      case _ => ServiceInfo(service, Seq(),None,Seq.empty, NOT_FOUND)
    }
  }

}

object MarathonSSEClient {

  case object Connected

  def getVHosts(app: MarathonAppPayload): Seq[String] = {
    lazy val HAPROXY_N_VHOST = "HAPROXY_([0-9]+)_VHOST".r
    if ( app.labels.get("HAPROXY_GROUP").contains("external") ) {
      app.labels.collect({
        case (HAPROXY_N_VHOST(_), vhost) => vhost
      }).map("https://" + _).toSeq
    }
    else Seq.empty
  }

  val logger: Logger = Logger(this.getClass())

  def parseEvent[T](event: ServerSentEvent)(implicit rds: play.api.libs.json.Reads[T]): Option[T] = {
    event.data filter {_.trim.nonEmpty} flatMap { data =>
      Try{Json.parse(data)} match {
        case Failure(e) =>
          logger.warn(s"error parsing event data as JSON:\n${data}", e)
          logger.warn(s"payload was:\n${data}")
          None
        case Success(js) =>
          js.validate[T] match {
            case JsError(_) =>
              logger.warn(s"error unmarshalling ${event.`type`} JSON")
              logger.warn(s"payload was:\n${data}")
              None
            case JsSuccess(obj, _) => Some(obj)
          }
      }
    }
  }

  implicit val formatSSE: OFormat[ServerSentEvent] = Json.format[ServerSentEvent]

}


