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
        id = Some("/gestalt-security"),
        args = Some(Seq("-J-Xmx512m")),
        env = Some(Json.obj(
          "OAUTH_RATE_LIMITING_PERIOD" -> "1",
          "DATABASE_HOSTNAME" -> "database",
          "DATABASE_NAME" -> "gestalt-security",
          "DATABASE_PASSWORD" -> "password",
          "DATABASE_PORT" -> "5432",
          "DATABASE_USERNAME" -> "gestalt-admin",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "100"
        )),
        instances = Some(1),
        cpus = Some(0.2),
        mem = Some(512),
        disk = Some(0),
        requirePorts = Some(true),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          docker = Some(MarathonDockerContainer(
            image = Some("galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a"),
            network = Some("BRIDGE"),
            privileged = Some(false),
            parameters = Some(Seq()),
            forcePullImage = Some(true),
            portMappings = Some(Seq(DockerPortMapping(
              containerPort = Some(9000),
              hostPort = Some(0),
              servicePort = Some(10104),
              protocol = Some("tcp"),
              labels = Some(Map(
                "VIP_0" -> "10.0.0.2:80"
              ))
            )))
          ))
        )),
        portDefinitions = Some(Seq(
          PortDefinition(port = Some(10104), name = Some("http-api"), protocol = Some("tcp"), labels = Some(Map()))
        )),
        labels = Some(Map(
          "HAPROXY_0_MODE" -> "http",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_GROUP" -> "external,internal"
        )),
        healthChecks = Some(Seq(MarathonHealthCheck(
          path = Some("/health"),
          protocol = Some("MESOS_HTTP"),
          portIndex = Some(0),
          gracePeriodSeconds = Some(300),
          intervalSeconds = Some(60),
          timeoutSeconds = Some(20),
          maxConsecutiveFailures = Some(3)
        )))
      )

      json.as[MarathonAppPayload] must_== expectedApp
      Json.toJson(expectedApp) must_== json
    }

    "parse properly from Marathon 1.5 return" in {
      val jsonStr =
        """{
          |  "args": [
          |    "-J-Xmx512m"
          |  ],
          |  "backoffFactor": 1.15,
          |  "backoffSeconds": 1,
          |  "container": {
          |    "docker": {
          |      "forcePullImage": true,
          |      "image": "galacticfog/gestalt-security:latest",
          |      "parameters": [],
          |      "privileged": false
          |    },
          |    "portMappings": [
          |      {
          |        "containerPort": 9000,
          |        "labels": {
          |          "VIP_0": "/gestalt-security:9455"
          |        },
          |        "name": "http",
          |        "protocol": "tcp",
          |        "servicePort": 10000
          |      }
          |    ],
          |    "type": "DOCKER",
          |    "volumes": []
          |  },
          |  "cpus": 0.2,
          |  "deployments": [
          |    {
          |      "id": "88b076fb-9333-4e1d-80a0-17db810c1fcf"
          |    }
          |  ],
          |  "disk": 0,
          |  "env": {
          |    "OAUTH_RATE_LIMITING_PERIOD": "1",
          |    "DATABASE_HOSTNAME": "database",
          |    "DATABASE_NAME": "gestalt-security",
          |    "DATABASE_PASSWORD": "password",
          |    "DATABASE_PORT": "5432",
          |    "DATABASE_USERNAME": "gestalt-admin",
          |    "OAUTH_RATE_LIMITING_AMOUNT": "100"
          |  },
          |  "executor": "",
          |  "gpus": 0,
          |  "healthChecks": [
          |    {
          |      "delaySeconds": 15,
          |      "gracePeriodSeconds": 300,
          |      "intervalSeconds": 30,
          |      "maxConsecutiveFailures": 4,
          |      "path": "/health",
          |      "portIndex": 0,
          |      "protocol": "MESOS_HTTP",
          |      "timeoutSeconds": 15
          |    }
          |  ],
          |  "id": "/gestalt-security",
          |  "instances": 1,
          |  "killSelection": "YOUNGEST_FIRST",
          |  "labels": {
          |    "HAPROXY_0_MODE": "http",
          |    "HAPROXY_0_VHOST": "security.galacticfog.com",
          |    "HAPROXY_GROUP": "external,internal"
          |  },
          |  "maxLaunchDelaySeconds": 3600,
          |  "mem": 512,
          |  "networks": [
          |    {
          |      "mode": "container",
          |      "name": "dcos"
          |    }
          |  ],
          |  "requirePorts": false,
          |  "tasks": [],
          |  "tasksHealthy": 0,
          |  "tasksRunning": 0,
          |  "tasksStaged": 0,
          |  "tasksUnhealthy": 0,
          |  "unreachableStrategy": {
          |    "expungeAfterSeconds": 0,
          |    "inactiveAfterSeconds": 0
          |  },
          |  "upgradeStrategy": {
          |    "maximumOverCapacity": 1,
          |    "minimumHealthCapacity": 1
          |  },
          |  "version": "2017-12-05T15:19:29.932Z"
          |}
        """.stripMargin

      val json = Json.parse(jsonStr)

      val expectedApp = MarathonAppPayload(
        id = Some("/gestalt-security"),
        args = Some(Seq("-J-Xmx512m")),
        env = Some(Json.obj(
          "OAUTH_RATE_LIMITING_PERIOD" -> "1",
          "DATABASE_HOSTNAME" -> "database",
          "DATABASE_NAME" -> "gestalt-security",
          "DATABASE_PASSWORD" -> "password",
          "DATABASE_PORT" -> "5432",
          "DATABASE_USERNAME" -> "gestalt-admin",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "100"
        )),
        instances = Some(1),
        cpus = Some(0.2),
        mem = Some(512),
        disk = Some(0),
        requirePorts = Some(false),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          docker = Some(MarathonDockerContainer(
            image = Some("galacticfog/gestalt-security:latest"),
            network = None,
            privileged = Some(false),
            parameters = Some(Seq()),
            forcePullImage = Some(true)
          )),
          portMappings = Some(Seq(DockerPortMapping(
            containerPort = Some(9000),
            hostPort = None,
            servicePort = Some(10000),
            protocol = Some("tcp"),
            name = Some("http"),
            labels = Some(Map(
              "VIP_0" -> "/gestalt-security:9455"
            ))
          ))),
          volumes = Some(Seq.empty)
        )),
        portDefinitions = None,
        networks = Some(Seq(Json.obj(
          "mode" -> "container",
          "name" -> "dcos"
        ))),
        labels = Some(Map(
          "HAPROXY_0_MODE" -> "http",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_GROUP" -> "external,internal"
        )),
        healthChecks = Some(Seq(MarathonHealthCheck(
          path = Some("/health"),
          protocol = Some("MESOS_HTTP"),
          portIndex = Some(0),
          gracePeriodSeconds = Some(300),
          intervalSeconds = Some(30),
          timeoutSeconds = Some(15),
          maxConsecutiveFailures = Some(4)
        ))),
        tasksStaged = Some(0),
        tasksHealthy = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasks = Some(Seq.empty)

      )
      json.as[MarathonAppPayload] must_== expectedApp
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
        id = Some("/app-with-secrets"),
        args = None,
        env = Some(Json.obj(
          "VAR1" -> "VAL1",
          "VAR_IS_SECRET" -> Json.obj(
            "secret" -> "some_secret"
          ),
          "VAR2" -> "VAL2"
        )),
        instances = Some(1),
        cpus = Some(0.2),
        mem = Some(512),
        disk = Some(0),
        requirePorts = Some(true),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = Some("name:tag"),
            network = Some("BRIDGE"),
            privileged = Some(false),
            parameters = Some(Seq()),
            forcePullImage = Some(false),
            portMappings = None
          ))
        )),
        portDefinitions = None,
        labels = Some(Map.empty),
        healthChecks = Some(Seq.empty)
      )

      json.as[MarathonAppPayload] must_== expectedApp
      Json.toJson(expectedApp) must_== json
    }

    "parse Marathon apps with both health check types" in {
      val jsonStr =
        """
          |{
          |  "id": "/app-with-diverse-health-checks",
          |  "cpus": 0.2,
          |  "mem": 512,
          |  "disk": 0,
          |  "instances": 1,
          |  "healthChecks": [
          |    {"port": 80},
          |    {"portIndex": 0}
          |  ],
          |  "labels": {},
          |  "env": {
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
        id = Some("/app-with-diverse-health-checks"),
        args = None,
        env = Some(Json.obj()),
        instances = Some(1),
        cpus = Some(0.2),
        mem = Some(512),
        disk = Some(0),
        requirePorts = Some(true),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = Some("name:tag"),
            network = Some("BRIDGE"),
            privileged = Some(false),
            parameters = Some(Seq()),
            forcePullImage = Some(false),
            portMappings = None
          ))
        )),
        portDefinitions = None,
        labels = Some(Map.empty),
        healthChecks = Some(Seq(
          MarathonHealthCheck(port = Some(80)), MarathonHealthCheck(portIndex = Some(0))
        ))
      )

      json.as[MarathonAppPayload] must_== expectedApp
      Json.toJson(expectedApp) must_== json
    }

  }

}
