package com.galacticfog.gestalt.dcos.marathon

import javax.inject.{Named, Inject}

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import de.heikoseeberger.akkasse.ServerSentEvent
import de.heikoseeberger.akkasse.pattern.ServerSentEventClient
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, JsSuccess, JsError, Json}
import play.api.libs.ws.WSClient
import play.api.{Logger => logger}

import akka.pattern.ask
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Try}
import play.api.libs.concurrent.Execution.Implicits.defaultContext


class MarathonSSEClient @Inject() (config: Configuration,
                                   lifecycle: ApplicationLifecycle,
                                   @Named("scheduler-actor") schedulerActor: ActorRef,
                                   gtf: GestaltTaskFactory,
                                   wsclient: WSClient)
                                  (implicit system: ActorSystem) {

  val marathonBaseUrl = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"

  val appGroup = config.getString("marathon.appGroup").getOrElse("gestalt").stripPrefix("/").stripSuffix("/")

  val allServices = gtf.allServices

  val STATUS_UPDATE_TIMEOUT = 15.seconds

  import JSONImports._

  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val marathon = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"
  logger.info(s"connecting to marathon event bus: ${marathon}")

  val handler = Sink.foreach[ServerSentEvent]{ event =>
    logger.info(s"marathon event: ${event.eventType}")
    if (event.eventType.exists(_ == "status_update_event")) Try{Json.parse(event.data)} match {
      case Failure(e) => logger.warn(s"error parsing status_update_event data:\n${event.data}", e)
      case Success(js) => js.validate[MarathonStatusUpdateEvent] match {
        case JsError(e) => logger.warn(s"error unmarshalling status_update_event JSON to MarathonStatusUpdateEvent:\n${e.toString}")
        case JsSuccess(statusUpdateEvent, _) =>
          logger.info("sending MarathonStatusUpdateEvent to scheduler-actor")
          schedulerActor ! statusUpdateEvent
      }
    }
  }
  ServerSentEventClient(s"${marathon}/v2/events", handler).runWith(Sink.ignore)

  lifecycle.addStopHook { () =>
    schedulerActor ! ShutdownRequest
    killApps.map {
      case true =>
        logger.info("shutdown was successful")
      case false =>
        logger.error("shutdown was not successful; manual cleanup may be necessary")
    }
  }

  def launchApp(appPayload: MarathonAppPayload): Future[JsValue] = {
    val appId = appPayload.id.stripPrefix("/")
    wsclient.url(s"${marathon}/v2/apps/${appId}").put(
      Json.toJson(appPayload)
    ).map { resp =>
      logger.info(s"launchApp(${appId}) response: ${resp.status}:${resp.statusText}")
      resp.json
    }
  }

  def killApps(): Future[Boolean] = {
    wsclient.url(s"${marathon}/v2/groups/${appGroup}")
      .withQueryString("force" -> "true")
      .delete()
      .map { _.status == 200 }
  }

  def getServiceStatus(name: String): Future[String] = {
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

            if (staged != 0) "STAGING"
            else if (target != running) "WAITING"
            else if (target == 0) "STOPPED"
            else if (sickly > 0) "UNHEALTHY"
            else if (target == healthy) "HEALTHY"
            else "RUNNING"
          })
        case 404 => Future.successful("NOT_STARTED")
        case not200 =>
          Future.failed(new RuntimeException(response.statusText))
      }
    } recover {
      case e: Throwable => s"error during fetch: ${e.getMessage}"
    }
  }

  def getAllServices(): Future[(StatusResponse,Map[String,String])] = {
    implicit val timeout: Timeout = STATUS_UPDATE_TIMEOUT
    val fResults = Future.sequence(allServices.map(name => getServiceStatus(name).map(f => name -> f)))
    val fStatus = (schedulerActor ? StatusRequest).map(_.asInstanceOf[StatusResponse])
    for {
      results <- fResults
      status <- fStatus
    } yield (status,results.toMap)
  }

}

object MarathonSSEClient {
  case object STREAM_CLOSED
}
