package com.galacticfog.gestalt.dcos.mesos

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.{Logger => logger}

class GestaltHttpSchedulerDriver @Inject()(config: Configuration,
                                           lifecycle: ApplicationLifecycle)
                                          (implicit system: ActorSystem) {

  import mesosphere.mesos.protos.{FrameworkID, FrameworkInfo}

  implicit val materializer = ActorMaterializer()

  val master = config.getString("mesos.master") getOrElse "master.mesos:5050"
  val (masterHost,masterPort) = master.split(":") match {
    case Array(host,port) => (host,port.toInt)
    case Array(host) => (host,5050)
    case _ => throw new RuntimeException("improperly formatted mesos.master")
  }

  logger.info(s"registering with master: ${masterHost}:${masterPort}")

  implicit val frameworkIdFmt = Json.format[FrameworkID]
  implicit val frameworkFmt = Json.format[FrameworkInfo]

  val registration = Json.obj(
    "type" -> "SUBSCRIBE",
    "subscribe" -> Json.obj(
      "framework_info" -> Json.obj(
        "user" -> "root",
        "name" -> "gestalt-dcos",
        "checkpoint" -> true,
        "failover_timeout" -> 60
      )
    )
  )

}
