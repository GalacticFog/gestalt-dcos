import com.galacticfog.gestalt.dcos.{GestaltTaskFactory, LauncherConfig}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.marathon._
import modules.Module
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.inject.bind
import play.api.libs.json.Json
import play.test.WithApplication

class PayloadGenerationSpec extends Specification with JsonMatchers {

  "Payload generation" should {

    lazy val testGlobalVars = Json.parse(
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

    "work from global (BRIDGE)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.tld" -> "galacticfog.com",
          "containers.security" -> "test-security:tag"
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
        args = Some(Seq("-J-Xmx768m")),
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
        mem = 1536,
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
              labels = Some(Map("VIP_0" -> "/gestalt-framework-tasks-security:9455"))
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
      val security = gtf.getMarathonPayload(SECURITY, global)
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
      val lambda = gtf.getMarathonPayload(LASER, global)
      lambda.container.docker must beSome
      lambda.container.docker.get.network must_== "HOST"
      lambda.container.docker.get.portMappings must beNone
      lambda.portDefinitions must beSome
    }

    "appropriately set realm override for security consumer services (TLD)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
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
      gtf.getMarathonPayload(META, global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
      gtf.getMarathonPayload(LASER, global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
      gtf.getMarathonPayload(API_GATEWAY, global).env must havePair("GESTALT_SECURITY_REALM" -> "https://security.galacticfog.com")
    }

    "appropriately set realm override for security consumer services (host IP)" in {
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

      gtf.getMarathonPayload(META, global).env must havePair("GESTALT_SECURITY_REALM"        -> "http://gestalt-framework-tasks-security.marathon.l4lb.thisdcos.directory:9455")
      gtf.getMarathonPayload(LASER, global).env must havePair("GESTALT_SECURITY_REALM"       -> "http://gestalt-framework-tasks-security.marathon.l4lb.thisdcos.directory:9455")
      gtf.getMarathonPayload(API_GATEWAY, global).env must havePair("GESTALT_SECURITY_REALM" -> "http://gestalt-framework-tasks-security.marathon.l4lb.thisdcos.directory:9455")
    }

    "appropriately set realm override for security consumer services (globals)" in {
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
          |     "realm" : "192.168.1.50:12345",
          |     "apiSecret": "apisecret"
          |  }
          |}
        """.stripMargin
      )
      gtf.getMarathonPayload(META, global).env must havePair("GESTALT_SECURITY_REALM"        -> "192.168.1.50:12345")
      gtf.getMarathonPayload(LASER, global).env must havePair("GESTALT_SECURITY_REALM"      -> "192.168.1.50:12345")
      gtf.getMarathonPayload(API_GATEWAY, global).env must havePair("GESTALT_SECURITY_REALM" -> "192.168.1.50:12345")
    }

    "database sets residency and grace period along with persistent storage" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]
      val data = gtf.getMarathonPayload(DATA(0), Json.obj())
      data.residency must beSome(Residency(Residency.WAIT_FOREVER))
      data.taskKillGracePeriodSeconds must beSome(300)
      data.container.volumes must beSome(containTheSameElementsAs(
        Seq(Volume("pgdata", "RW", Some(VolumePersistence(config.database.provisionedSize))))
      ))
    }

    "set framework labels on laser scheduler" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.app-group" -> "/gestalt/test/east/"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
      laserPayload.labels must havePairs(
        "DCOS_PACKAGE_FRAMEWORK_NAME" -> "gestalt-test-east-laser",
        "DCOS_PACKAGE_IS_FRAMEWORK" -> "true"
      )
      laserPayload.env must havePair(
        "SCHEDULER_NAME" -> "gestalt-test-east-laser"
      )
    }

    "set min-cool, scaledown-timeout vars on laser scheduler per config" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "laser.scale-down-timeout" -> 300,
          "laser.min-cool-executors" -> 10
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
      laserPayload.env must havePairs(
        "MIN_COOL_EXECUTORS" -> "10",
        "SCALE_DOWN_TIME_SECONDS" -> "300"
      )
    }

    "set default min-cool, scaledown-timeout vars on laser scheduler" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
      laserPayload.env must havePairs(
        "MIN_COOL_EXECUTORS" -> LauncherConfig.LaserConfig.DEFAULT_MIN_COOL_EXECS.toString,
        "SCALE_DOWN_TIME_SECONDS" -> LauncherConfig.LaserConfig.DEFAULT_SCALE_DOWN_TIMEOUT.toString
      )
    }

    "set port range vars on laser scheduler per config" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "laser.min-port-range" -> 100,
          "laser.max-port-range" -> 200
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
      laserPayload.env must havePairs(
        "MIN_PORT_RANGE" -> "100",
        "MAX_PORT_RANGE" -> "200"
      )
    }

    "set default port range vars on laser scheduler" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
      laserPayload.env must havePairs(
        "MIN_PORT_RANGE" -> LauncherConfig.LaserConfig.DEFAULT_MIN_PORT_RANGE.toString,
        "MAX_PORT_RANGE" -> LauncherConfig.LaserConfig.DEFAULT_MAX_PORT_RANGE.toString
      )
    }

    "use pgrepl database container for primary and secondary database containers" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "database.num-secondaries" -> 2
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      gtf.getMarathonPayload(DATA(0), testGlobalVars).container.docker must beSome((d: MarathonDockerContainer) => d.image.startsWith("galacticfog/postgres_repl:"))
      gtf.getMarathonPayload(DATA(1), testGlobalVars).container.docker must beSome((d: MarathonDockerContainer) => d.image.startsWith("galacticfog/postgres_repl:"))
      gtf.getMarathonPayload(DATA(2), testGlobalVars).container.docker must beSome((d: MarathonDockerContainer) => d.image.startsWith("galacticfog/postgres_repl:"))
    }

    "acknowledge the appropriate number of DATA stages and services according to config" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "database.num-secondaries" -> 3
        )
        .injector
      val config = injector.instanceOf[LauncherConfig]
      config.provisionedServices.filter(_.isInstanceOf[DATA]) must containTheSameElementsAs(
        Seq(DATA(0), DATA(1), DATA(2), DATA(3))
      )
    }

    "acknowledge the appropriate number of DATA stages and services according to default" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val config = injector.instanceOf[LauncherConfig]
      assert(LauncherConfig.DatabaseConfig.DEFAULT_NUM_SECONDARIES == 0)
      config.provisionedServices.filter(_.isInstanceOf[DATA]) must containTheSameElementsAs(
        Seq(DATA(0))
      )
    }

    "configure first database container as primary" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.app-group" -> "/gestalt"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getMarathonPayload(DATA(0), testGlobalVars)
      payload.env must havePair("PGREPL_ROLE" -> "PRIMARY")
      Json.toJson(payload).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-primary:5432")
    }

    "configure database replication with consistent password" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val pay0 = gtf.getMarathonPayload(DATA(0), testGlobalVars)
      val pay1 = gtf.getMarathonPayload(DATA(1), testGlobalVars)
      pay0.env must haveKey("PGREPL_TOKEN")
      pay1.env must haveKey("PGREPL_TOKEN")
      pay0.env("PGREPL_TOKEN") must_== pay1.env("PGREPL_TOKEN")
    }

    "configure later database containers as secondary" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.app-group" -> "/gestalt"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val standbyvars = Seq(
        "PGREPL_ROLE" -> "STANDBY",
        "PGREPL_MASTER_IP" -> "gestalt-data-primary.marathon.l4lb.thisdcos.directory",
        "PGREPL_MASTER_PORT" -> "5432"
      )
      gtf.getMarathonPayload(DATA(1), testGlobalVars).env must havePairs(standbyvars:_*)
      gtf.getMarathonPayload(DATA(2), testGlobalVars).env must havePairs(standbyvars:_*)
      gtf.getMarathonPayload(DATA(3), testGlobalVars).env must havePairs(standbyvars:_*)
      gtf.getMarathonPayload(DATA(10), testGlobalVars).env must havePairs(standbyvars:_*)
    }

    "set either args or cmd on marathon payloads to satisfy DCOS 1.8 schema" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      Fragment.foreach(config.provisionedServices) { svc =>
        val payload = gtf.getMarathonPayload(svc, testGlobalVars)
        svc.name ! {(payload.cmd must beSome) or (payload.args must beSome)}
      }
    }

  }

}
