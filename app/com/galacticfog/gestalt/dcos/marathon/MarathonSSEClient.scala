package com.galacticfog.gestalt.dcos.marathon

import javax.inject.{Named, Inject}

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import de.heikoseeberger.akkasse.ServerSentEvent
import de.heikoseeberger.akkasse.pattern.ServerSentEventClient
import play.api.Configuration
import play.api.libs.json.Json
import play.api.{Logger => logger}

import scala.util.Try

class MarathonSSEClient @Inject() (config: Configuration,
                                   @Named("scheduler-actor") schedulerActor: ActorRef)
                                  (implicit system: ActorSystem) {

  import JSONImports._

  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val marathon = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"
  logger.info(s"connecting to marathon event bus: ${marathon}")

  val handler = Sink.foreach[ServerSentEvent]{ event =>
    logger.info(s"marathon event: ${event.eventType}")
    if (event.eventType.exists(_ == "status_update_event")) Try{Json.parse(event.data)} foreach {
      _.validate[MarathonStatusUpdateEvent].foreach {
        e => schedulerActor ! e
      }
    }
  }
  ServerSentEventClient(s"${marathon}/v2/events", handler).runWith(Sink.ignore)
}

object MarathonSSEClient {
  case object STREAM_CLOSED
}
