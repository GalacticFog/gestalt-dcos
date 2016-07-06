import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.marathon._
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class PayloadGenerationSpec extends Specification with JsonMatchers {

  "Payload generation" should {

    "work from global" in {

      val global = Json.parse(
        """{
          |  "marathon": {
          |     "appGroup": "gestalt",
          |     "url": "http://marathon.mesos:8080",
          |     "tld": "galacticfog.com"
          |  },
          |  "database": {
          |     "hostname": "test-db.marathon.mesos",
          |     "port": 5432,
          |     "username": "test-user",
          |     "password": "test-password",
          |     "prefix": "test-"
          |  },
          |  "security": {
          |     "oauth": {
          |       "rateLimitingPeriod": 5,
          |       "rateLimitingAmount": 1000
          |     }
          |  }
          |}
        """.stripMargin
      )

      val expected = MarathonAppPayload(
        id = "/gestalt/security",
        args = Some(Seq("-J-Xmx512m")),
        env = Map(
          "OAUTH_RATE_LIMITING_PERIOD" -> "5",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "1000",
          "DATABASE_HOSTNAME" -> "test-db.marathon.mesos",
          "DATABASE_PORT" -> "5432",
          "DATABASE_NAME" -> "test-security",
          "DATABASE_USERNAME" -> "test-user",
          "DATABASE_PASSWORD" -> "test-password"
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
              protocol = "tcp",
              labels = Some(Map("VIP_0" -> "10.99.99.12:80"))
            )))
          ))
        ),
        labels = Map("HAPROXY_0_VHOST" -> "security.galacticfog.com", "HAPROXY_GROUP" -> "external"),
        healthChecks = Seq(MarathonHealthCheck(
          path = "/health",
          protocol = "HTTP",
          portIndex = 0,
          gracePeriodSeconds = 300,
          intervalSeconds = 60,
          timeoutSeconds = 20,
          maxConsecutiveFailures = 3
        ))
      )

      val gtf = new GestaltTaskFactory

      val security = gtf.toMarathonPayload(gtf.getAppSpec("security", global), global)
      security must_== expected

    }

  }

}
