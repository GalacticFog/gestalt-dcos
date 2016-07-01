package com.galacticfog.gestalt.dcos.marathon

import play.api.libs.json.JsValue
import scala.util.{Failure, Try}

trait AppPayloadFactory {
  def generate(globals: JsValue,
               services: Map[String, MarathonAppPayload]): Try[MarathonAppPayload]

}

object AppPayloadFactory {

  val factories: Map[String,AppPayloadFactory] = Map(
    "security" -> SecurityFactory
  )

  def generate(globals: JsValue,
               serviceName: String,
               services: Map[String, MarathonAppPayload]): Try[MarathonAppPayload] = {
    factories.get(serviceName).fold[Try[MarathonAppPayload]](
      Failure(new RuntimeException("could not locate factory for service " + serviceName))
    )(
      _.generate(globals, services)
    )
  }
}

object SecurityFactory extends AppPayloadFactory {
  override def generate(globals: JsValue,
                        services: Map[String, MarathonAppPayload]): Try[MarathonAppPayload] = {
    val prefix = (globals \ "marathon" \ "appGroup").asOpt[String] getOrElse "gestalt"
    val cleanPrefix = "/" + prefix.stripPrefix("/").stripSuffix("/") + "/"
    Try{MarathonAppPayload(
      id = cleanPrefix + "security",
      args = Some(Seq("-J-Xmx512m")),
      env = Map(
        "OAUTH_RATE_LIMITING_PERIOD" -> "1",
        "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
        "DATABASE_HOSTNAME" -> "psql.marathon.mesos",
        "DATABASE_NAME" -> "gestalt-security",
        "DATABASE_USERNAME" -> "gestalt-admin",
        "DATABASE_PASSWORD" -> "db-password",
        "DATABASE_PORT" -> "5432"
      ),
      instances = 1,
      cpus = 0.5,
      mem = 768,
      disk = 0,
      requirePorts = true,
      container = MarathonContainerInfo(
        containerType = "DOCKER",
        docker = Some(MarathonDockerContainer(
          image = "galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a",
          network = "BRIDGE",
          privileged = false,
          parameters = Seq(),
          forcePullImage = true,
          portMappings = Some(Seq(DockerPortMapping(
            containerPort = 9000,
            protocol = "tcp"
          )))
        ))
      ),
      labels = Map(),
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0,
        gracePeriodSeconds = 300,
        intervalSeconds = 60,
        timeoutSeconds = 20,
        maxConsecutiveFailures = 3
      ))
    )}
  }
}
