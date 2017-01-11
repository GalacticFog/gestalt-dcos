package com.galacticfog.gestalt.dcos.marathon

import javax.inject.{Inject, Named}

import scala.language.postfixOps
import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.galacticfog.gestalt.dcos.{GestaltTaskFactory, LauncherConfig}
import play.api.Configuration
import play.api.libs.ws.WSClient
import play.api.{Logger => logger}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import de.heikoseeberger.akkasse.{EventStreamUnmarshalling, ServerSentEvent}
import akka.http.scaladsl.client.RequestBuilding.Get
import de.heikoseeberger.akkasse.MediaTypes.`text/event-stream`

sealed trait ServiceStatus
final case object LAUNCHING extends ServiceStatus
final case object STAGING   extends ServiceStatus
final case object WAITING   extends ServiceStatus
final case object STOPPED   extends ServiceStatus
final case object UNHEALTHY extends ServiceStatus
final case object HEALTHY   extends ServiceStatus
final case object RUNNING   extends ServiceStatus
final case object NOT_FOUND extends ServiceStatus

case class ServiceInfo(serviceName: String, vhosts: Seq[String], hostname: Option[String], ports: Seq[String], status: ServiceStatus)

case object ServiceInfo {
  implicit val statusFmt = new Writes[ServiceStatus] {
    override def writes(o: ServiceStatus): JsValue = JsString(o.toString)
  }
  implicit val serviceInfoWrites = Json.writes[ServiceInfo]
}

object BiggerUnmarshalling extends EventStreamUnmarshalling {
  override protected def maxLineSize: Int = 524288
  override protected def maxEventSize: Int = 524288
}

class MarathonSSEClient @Inject() ( launcherConfig: LauncherConfig,
                                    @Named("scheduler-actor") schedulerActor: ActorRef,
                                    wsclient: WSClient )
                                  ( implicit system: ActorSystem ) {

  import system.dispatcher
  import MarathonSSEClient._
  import BiggerUnmarshalling._

  val marathonBaseUrl = launcherConfig.marathon.baseUrl

  val appGroup = launcherConfig.marathon.appGroup.stripPrefix("/").stripSuffix("/")
  val appIdWithGroup = s"/${appGroup}/(.*)".r

  val STATUS_UPDATE_TIMEOUT = 15.seconds

  implicit val mat = ActorMaterializer()

  logger.info(s"connecting to marathon event bus: ${marathonBaseUrl}")

  val handler = Sink.actorRef(schedulerActor, Done)
  Http(system)
    .singleRequest(
      Get(s"${marathonBaseUrl}/v2/events")
        .addHeader(
          Accept(`text/event-stream`)
        )
    )
    .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
    .foreach(_.runWith(handler))


  def launchApp(appPayload: MarathonAppPayload): Future[JsValue] = {
    val appId = appPayload.id.stripPrefix("/")
    wsclient.url(s"${marathonBaseUrl}/v2/apps/${appId}")
      .withQueryString("force" -> "true")
      .put(
        Json.toJson(appPayload)
      ).flatMap { resp =>
      resp.status match {
        case 201 => Future.successful(resp.json)
        case 200 => Future.successful(resp.json)
        case not201 =>
          logger.info(s"launchApp(${appId}) response: ${resp.status}:${resp.statusText}")
          Future.failed(new RuntimeException(
            Try{(resp.json \ "message").as[String]} getOrElse resp.body
          ))
      }
    }
  }

  def killApp(svcName: String): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${svcName}")
    val appId = s"/${appGroup}/${svcName}"
    wsclient.url(s"${marathonBaseUrl}/v2/apps${appId}")
      .withQueryString("force" -> "true")
      .delete()
      .map { resp =>
        logger.info(s"marathon.delete(${svcName}) => ${resp.statusText}")
        if (resp.status == 200 || resp.status == 404) schedulerActor ! MarathonAppTerminatedEvent(appId, "app_terminated_event", "")
        resp.status == 200
      }
  }

  def stopApp(svcName: String): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${svcName}")
    wsclient.url(s"${marathonBaseUrl}/v2/apps/${appGroup}/${svcName}")
      .withQueryString("force" -> "true")
      .put(Json.obj("instances" -> 0))
      .map { resp =>
        logger.info(s"marathon.stop(${svcName}) => ${resp.statusText}")
        resp.status == 200
      }
  }

  def getServices(): Future[Map[String,ServiceInfo]] = {
    val url = marathonBaseUrl.stripSuffix("/")
    wsclient.url(s"${url}/v2/groups/${appGroup}").withQueryString("embed" -> "group.apps", "embed" -> "group.apps.counts", "embed" -> "group.apps.tasks").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get().flatMap { response =>
      response.status match {
        case 200 =>
          Future.fromTry(Try {
            (response.json \ "apps").as[Seq[MarathonAppPayload]].flatMap {
              app => appIdWithGroup.findFirstMatchIn(app.id) map (m => (m.group(1),app))
            } map { case (name,app) =>

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

              val vhosts = getVHosts(app)

              val hostname = app.tasks.flatMap(_.headOption).flatMap(_.host)
              val ports = app.tasks.flatMap(_.headOption).flatMap(_.ports).map(_.map(_.toString)) getOrElse Seq.empty

              logger.debug(s"processed app from marathon: ${name}")
              name -> ServiceInfo(name,vhosts,hostname,ports,status)
            } toMap
          })
        case 404 => Future.successful(Map.empty)
        case not200 =>
          Future.failed(new RuntimeException(response.statusText))
      }
    }
  }

  def getServiceStatus(name: String): Future[ServiceInfo] = {
    val url = marathonBaseUrl.stripSuffix("/")
    wsclient.url(s"${url}/v2/apps/${appGroup}/${name}").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get().flatMap { response =>
      response.status match {
        case 200 =>
          Future.fromTry(Try {
            val app = (response.json \ "app").as[MarathonAppPayload]

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

            val vhosts = getVHosts(app)

            val hostname = app.tasks.flatMap(_.headOption).flatMap(_.host)
            val ports = app.tasks.flatMap(_.headOption).flatMap(_.ports).map(_.map(_.toString)) getOrElse Seq.empty

            ServiceInfo(name,vhosts,hostname,ports,status)
          })
        case 404 => Future.successful(ServiceInfo(name,Seq(),None,Seq.empty,NOT_FOUND))
        case not200 =>
          Future.failed(new RuntimeException(response.statusText))
      }
    } recover {
      case e: Throwable => ServiceInfo(name, Seq(),None,Seq.empty, NOT_FOUND)
    }
  }

}

object MarathonSSEClient {
  def getVHosts(app: MarathonAppPayload): Seq[String] = app.labels.filterKeys(_.matches("HAPROXY_[0-9]+_VHOST")).values.toSeq

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

}


