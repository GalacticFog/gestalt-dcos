package com.galacticfog.gestalt.dcos.marathon

import javax.inject.{Named, Inject}

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
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
                                   wsclient: WSClient)
                                  (implicit system: ActorSystem) {

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
    wsclient.url(s"${marathon}/v2/groups/gestalt")
      .withQueryString("force" -> "true")
      .delete()
      .map { _.status == 200 }
  }

}

object MarathonSSEClient {
  case object STREAM_CLOSED
}
