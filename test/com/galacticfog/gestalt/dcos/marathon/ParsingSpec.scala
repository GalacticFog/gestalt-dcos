package com.galacticfog.gestalt.dcos.marathon

import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import play.api.libs.json.Json

import scala.util.Try

class ParsingSpec extends Specification with JsonMatchers {

  "MarathonAppPayload" should {

    "parse properly from Marathon return" in {
      val jsonStr =
        """
          |{
          |  "id": "/gestalt-security",
          |  "cpus": 0.2,
          |  "mem": 512,
          |  "disk": 0,
          |  "instances": 1,
          |  "env": {
          |    "OAUTH_RATE_LIMITING_PERIOD": "1",
          |    "DATABASE_HOSTNAME": "database",
          |    "DATABASE_NAME": "gestalt-security",
          |    "DATABASE_PASSWORD": "password",
          |    "DATABASE_PORT": "5432",
          |    "DATABASE_USERNAME": "gestalt-admin",
          |    "OAUTH_RATE_LIMITING_AMOUNT": "100"
          |  },
          |  "healthChecks": [
          |    {
          |      "path": "/health",
          |      "protocol": "MESOS_HTTP",
          |      "portIndex": 0,
          |      "gracePeriodSeconds": 300,
          |      "intervalSeconds": 60,
          |      "timeoutSeconds": 20,
          |      "maxConsecutiveFailures": 3
          |    }
          |  ],
          |  "labels": {
          |    "HAPROXY_0_MODE": "http",
          |    "HAPROXY_0_VHOST": "security.galacticfog.com",
          |    "HAPROXY_GROUP": "external,internal"
          |  },
          |  "requirePorts": true,
          |  "portDefinitions": [
          |    {
          |      "port": 10104,
          |      "protocol": "tcp",
          |      "name": "http-api",
          |      "labels": {}
          |    }
          |  ],
          |  "args": [
          |    "-J-Xmx512m"
          |  ],
          |  "container": {
          |    "type": "DOCKER",
          |    "docker": {
          |      "image": "galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a",
          |      "network": "BRIDGE",
          |      "portMappings": [
          |        {
          |          "containerPort": 9000,
          |          "hostPort": 0,
          |          "servicePort": 10104,
          |          "protocol": "tcp",
          |          "labels": {
          |            "VIP_0": "10.0.0.2:80"
          |          }
          |        }
          |      ],
          |      "privileged": false,
          |      "parameters": [],
          |      "forcePullImage": true
          |    }
          |  }
          |}
        """.stripMargin

      val json = Json.parse(jsonStr)

      val expectedApp = MarathonAppPayload(
        id = "/gestalt-security",
        args = Some(Seq("-J-Xmx512m")),
        env = Json.obj(
          "OAUTH_RATE_LIMITING_PERIOD" -> "1",
          "DATABASE_HOSTNAME" -> "database",
          "DATABASE_NAME" -> "gestalt-security",
          "DATABASE_PASSWORD" -> "password",
          "DATABASE_PORT" -> "5432",
          "DATABASE_USERNAME" -> "gestalt-admin",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "100"
        ),
        instances = 1,
        cpus = 0.2,
        mem = 512,
        disk = 0,
        requirePorts = true,
        container = MarathonContainerInfo(
          `type` = "DOCKER",
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = "galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a",
            network = "BRIDGE",
            privileged = false,
            parameters = Seq(),
            forcePullImage = true,
            portMappings = Some(Seq(DockerPortMapping(
              containerPort = 9000,
              hostPort = Some(0),
              servicePort = Some(10104),
              protocol = "tcp",
              labels = Some(Map(
                "VIP_0" -> "10.0.0.2:80"
              ))
            )))
          ))
        ),
        portDefinitions = Some(Seq(
          PortDefinition(port = 10104, name = Some("http-api"), protocol = "tcp", labels = Some(Map()))
        )),
        labels = Map(
          "HAPROXY_0_MODE" -> "http",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_GROUP" -> "external,internal"
        ),
        healthChecks = Seq(MarathonHealthCheck(
          path = Some("/health"),
          protocol = "MESOS_HTTP",
          portIndex = 0,
          gracePeriodSeconds = 300,
          intervalSeconds = 60,
          timeoutSeconds = 20,
          maxConsecutiveFailures = 3
        ))
      )

      json.as[MarathonAppPayload] must_== expectedApp
      Json.toJson(expectedApp) must_== json
    }

    "parse Marathon apps with secrets" in {
      val jsonStr =
        """
          |{
          |  "id": "/app-with-secrets",
          |  "cpus": 0.2,
          |  "mem": 512,
          |  "disk": 0,
          |  "instances": 1,
          |  "healthChecks": [],
          |  "labels": {},
          |  "env": {
          |    "VAR1": "VAL1",
          |    "VAR_IS_SECRET": {
          |       "secret": "some_secret"
          |    },
          |    "VAR2": "VAL2"
          |  },
          |  "requirePorts": true,
          |  "container": {
          |    "type": "DOCKER",
          |    "docker": {
          |      "image": "name:tag",
          |      "network": "BRIDGE",
          |      "parameters": [],
          |      "privileged": false,
          |      "forcePullImage": false
          |    }
          |  }
          |}
        """.stripMargin

      val json = Json.parse(jsonStr)

      val expectedApp = MarathonAppPayload(
        id = "/app-with-secrets",
        args = None,
        env = Json.obj(
          "VAR1" -> "VAL1",
          "VAR_IS_SECRET" -> Json.obj(
            "secret" -> "some_secret"
          ),
          "VAR2" -> "VAL2"
        ),
        instances = 1,
        cpus = 0.2,
        mem = 512,
        disk = 0,
        requirePorts = true,
        container = MarathonContainerInfo(
          `type` = "DOCKER",
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = "name:tag",
            network = "BRIDGE",
            privileged = false,
            parameters = Seq(),
            forcePullImage = false,
            portMappings = None
          ))
        ),
        portDefinitions = None,
        labels = Map.empty,
        healthChecks = Seq.empty
      )

      json.as[MarathonAppPayload] must_== expectedApp
      Json.toJson(expectedApp) must_== json
    }

  }

}
