import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.marathon._
import modules.Module
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import play.api.inject.guice.{GuiceInjectorBuilder, GuiceApplicationBuilder}
import play.api.inject.bind
import play.api.libs.json.Json
import play.test.WithApplication

class PayloadGenerationSpec extends Specification with JsonMatchers {

  "Payload generation" should {

    "work from global (BRIDGE)" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "service.vip" -> "10.11.12.13",
          "marathon.tld" -> "galacticfog.com",
          "containers.gestalt-security" -> "test-security:tag"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.parse(
        """{
          |  "marathon": {
          |     "appGroup": "gestalt",
          |     "url": "http://marathon.mesos:8080"
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
        id = "/gestalt-framework-tasks/security",
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
          `type` = "DOCKER",
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = "test-security:tag",
            network = "BRIDGE",
            privileged = false,
            parameters = Seq.empty,
            forcePullImage = true,
            portMappings = Some(Seq(DockerPortMapping(
              containerPort = 9000,
              protocol = "tcp",
              name = Some("http-api"),
              labels = Some(Map("VIP_0" -> "10.11.12.13:9455"))
            )))
          ))
        ),
        labels = Map("HAPROXY_0_VHOST" -> "security.galacticfog.com", "HAPROXY_GROUP" -> "external"),
        healthChecks = Seq(MarathonHealthCheck(
          path = "/health",
          protocol = "HTTP",
          portIndex = 0,
          gracePeriodSeconds = 300,
          intervalSeconds = 30,
          timeoutSeconds = 15,
          maxConsecutiveFailures = 4
        )),
        readinessCheck = Some(MarathonReadinessCheck(
          protocol = "HTTP",
          path = "/init",
          portName = "http-api",
          intervalSeconds = 5
        ))
      )

      val security = gtf.getMarathonPayload("security", global)
      security must_== expected

    }

    "map HOST ports appropriately" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

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
          |     "apiKey": "apikey",
          |     "apiSecret": "apisecret",
          |     "oauth": {
          |       "rateLimitingPeriod": 5,
          |       "rateLimitingAmount": 1000
          |     }
          |  }
          |}
        """.stripMargin
      )


      val lambda = gtf.getMarathonPayload("lambda", global)
      lambda.container.docker must beSome
      lambda.container.docker.get.network must_== "HOST"
      lambda.container.docker.get.portMappings must beNone
      lambda.portDefinitions must beSome
    }

    "appropriately set realm override for security consumer services (TLD)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "service.vip" -> "10.11.12.13",
          "marathon.tld" -> "galacticfog.com"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.parse(
        """{
          |  "database": {
          |     "hostname": "test-db.marathon.mesos",
          |     "port": 5432,
          |     "username": "test-user",
          |     "password": "test-password",
          |     "prefix": "test-"
          |  },
          |  "security": {
          |     "apiKey": "apikey",
          |     "realm" : "192.168.1.50:12345",
          |     "apiSecret": "apisecret"
          |  }
          |}
        """.stripMargin
      )

      gtf.getMarathonPayload("meta", global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
      gtf.getMarathonPayload("lambda", global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
      gtf.getMarathonPayload("api-gateway", global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
    }

    "appropriately set realm override for security consumer services (host IP)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "service.vip" -> "10.11.12.13"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.parse(
        """{
          |  "database": {
          |     "hostname": "test-db.marathon.mesos",
          |     "port": 5432,
          |     "username": "test-user",
          |     "password": "test-password",
          |     "prefix": "test-"
          |  },
          |  "security": {
          |     "apiKey": "apikey",
          |     "apiSecret": "apisecret"
          |  }
          |}
        """.stripMargin
      )

      gtf.getMarathonPayload("meta", global).env must havePair("GESTALT_SECURITY_REALM" -> "http://10.11.12.13:9455")
      gtf.getMarathonPayload("lambda", global).env must havePair("GESTALT_SECURITY_REALM" -> "http://10.11.12.13:9455")
      gtf.getMarathonPayload("api-gateway", global).env must havePair("GESTALT_SECURITY_REALM" -> "http://10.11.12.13:9455")
    }

    "appropriately set realm override for security consumer services (globals)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "service.vip" -> "10.11.12.13"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.parse(
        """{
          |  "database": {
          |     "hostname": "test-db.marathon.mesos",
          |     "port": 5432,
          |     "username": "test-user",
          |     "password": "test-password",
          |     "prefix": "test-"
          |  },
          |  "security": {
          |     "apiKey": "apikey",
          |     "realm" : "192.168.1.50:12345",
          |     "apiSecret": "apisecret"
          |  }
          |}
        """.stripMargin
      )

      gtf.getMarathonPayload("meta", global).env must havePair("GESTALT_SECURITY_REALM"        -> "192.168.1.50:12345")
      gtf.getMarathonPayload("lambda", global).env must havePair("GESTALT_SECURITY_REALM"      -> "192.168.1.50:12345")
      gtf.getMarathonPayload("api-gateway", global).env must havePair("GESTALT_SECURITY_REALM" -> "192.168.1.50:12345")
    }

    "set either args or cmd on marathon payloads to satisfy DCOS 1.8 schema" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.parse(
        """{
          |  "database": {
          |     "hostname": "test-db.marathon.mesos",
          |     "port": 5432,
          |     "username": "test-user",
          |     "password": "test-password",
          |     "prefix": "test-"
          |  },
          |  "security": {
          |     "apiKey": "apikey",
          |     "apiSecret": "apisecret"
          |  }
          |}
        """.stripMargin
      )
      Fragment.foreach(gtf.allServices) { svc =>
        val payload = gtf.getMarathonPayload(svc, global)
        svc ! {(payload.cmd must beSome) or (payload.args must beSome)}
      }
    }

    "database sets residency along with persistent storage" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]

      val global = Json.obj()
      val data = gtf.getMarathonPayload("data", global)
      data.residency must beSome(Residency(Residency.WAIT_FOREVER))
    }

  }

}
