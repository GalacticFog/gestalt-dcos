import com.galacticfog.gestalt.dcos.marathon.{HealthCheck, PortDefinition, MarathonAppPayload}
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import org.specs2.mutable._
import org.specs2.mutable.Specification
import org.specs2.matcher.JsonMatchers
import org.specs2.runner._
import com.galacticfog.gestalt.dcos.marathon.JSONImports._

class ParsingSpec extends Specification with JsonMatchers {

  "MarathonAppPayload" should {

    "parse properly from Marathon return" in {
      val jsonStr =
        """
          |{
          |  "id": "/gestalt-security",
          |  "cmd": null,
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
          |      "protocol": "HTTP",
          |      "portIndex": 0,
          |      "gracePeriodSeconds": 300,
          |      "intervalSeconds": 60,
          |      "timeoutSeconds": 20,
          |      "maxConsecutiveFailures": 3,
          |      "ignoreHttp1xx": false
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
          |      "labels": {}
          |    }
          |  ],
          |  "args": [
          |    "-J-Xmx512m"
          |  ],
          |  "container": {
          |    "type": "DOCKER",
          |    "volumes": [],
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
        env = Map(
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
        portDefinitions = Some(Seq(
          PortDefinition(port = 10104, "tcp", Some(Map()))
        )),
        labels = Map(
          "HAPROXY_0_MODE" -> "http",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_GROUP" -> "external,internal"
        ),
        healthChecks = Seq(HealthCheck(
          path = "/health",
          protocol = "HTTP",
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

  }

}
