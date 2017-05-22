import java.util.UUID

import com.galacticfog.gestalt.dcos._
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.marathon._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import modules.Module
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}

class PayloadGenerationSpec extends Specification with JsonMatchers {

  def uuid = UUID.randomUUID()

  def haveEnvVar(pair: => (String, String)) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("private") /(pair)

  "Payload generation" should {

    val testGlobalVars = GlobalConfig().withDb(GlobalDBConfig(
      hostname = "test-db.marathon.mesos",
      port = 5432,
      username = "test-user",
      password = "test-password",
      prefix = "test-"
    )).withSec(GlobalSecConfig(
      hostname = "security",
      port = 9455,
      apiKey = "key",
      apiSecret = "secret",
      realm = Some("192.168.1.50:12345")
    ))

    "work from global config (BRIDGE)" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.tld" -> "galacticfog.com",
          "containers.security" -> "test-security:tag"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val globalConfig = GlobalConfig().withDb(GlobalDBConfig(
        hostname = "test-db.marathon.mesos",
        port = 5432,
        username = "test-user",
        password = "test-password",
        prefix = "test-"
      ))
      val expected = MarathonAppPayload(
        id = "/gestalt-framework-tasks/security",
        args = Some(Seq("-J-Xmx768m")),
        env = Map(
          "OAUTH_RATE_LIMITING_PERIOD" -> "1",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
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
            portMappings = Some(Seq(
              DockerPortMapping(
                containerPort = 9000,
                protocol = "tcp",
                name = Some("http-api"),
                labels = Some(Map("VIP_0" -> "/gestalt-framework-tasks-security:9455"))
              ),
              DockerPortMapping(
                containerPort = 9000,
                protocol = "tcp",
                name = Some("http-api-dupe"),
                labels = Some(Map())
              )
            ))
          ))
        ),
        labels = Map(
          "HAPROXY_GROUP" -> "external",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_1_VHOST" -> "galacticfog.com",
          "HAPROXY_1_PATH" -> "/security",
          "HAPROXY_1_HTTP_BACKEND_PROXYPASS_PATH" -> "/security"
        ),
        healthChecks = Seq(MarathonHealthCheck(
          path = Some("/health"),
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
      val security = gtf.getMarathonPayload(SECURITY, globalConfig)
      security must_== expected
    }

    "appropriately set realm override for security consumer services" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.tld" -> "galacticfog.com"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val global = GlobalConfig().withDb(GlobalDBConfig(
        hostname = "test-db.marathon.mesos",
        port = 5432,
        username = "test-user",
        password = "test-password",
        prefix = "test-"
      )).withSec(GlobalSecConfig(
        hostname = "security",
        port = 9455,
        apiKey = "key",
        apiSecret = "secret",
        realm = Some("https://security.galacticfog.com")
      ))

      val realm = "https://security.galacticfog.com"
      gtf.getMarathonPayload(META, global).env must havePair("GESTALT_SECURITY_REALM" -> realm)
      gtf.getSecurityProvider(global.secConfig.get).toString must /("properties") /("config") /("env") /("public") /("REALM" -> realm)
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
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      laserPayload must haveEnvVar("MIN_COOL_EXECUTORS" -> "10")
      laserPayload must haveEnvVar("SCALE_DOWN_TIME_SECONDS" -> "300")
    }

    "set default min-cool, scaledown-timeout vars on laser scheduler" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      laserPayload must haveEnvVar("MIN_COOL_EXECUTORS" -> LauncherConfig.LaserConfig.DEFAULT_MIN_COOL_EXECS.toString)
      laserPayload must haveEnvVar("SCALE_DOWN_TIME_SECONDS" -> LauncherConfig.LaserConfig.DEFAULT_SCALE_DOWN_TIMEOUT.toString)
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
      ko("update me")
//      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
//      laserPayload.env must havePairs(
//        "MIN_PORT_RANGE" -> "100",
//        "MAX_PORT_RANGE" -> "200"
//      )
    }.pendingUntilFixed

    "set default port range vars on laser scheduler" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      ko("update me")
//      val laserPayload = gtf.getMarathonPayload(LASER, testGlobalVars)
//      laserPayload.env must havePairs(
//        "MIN_PORT_RANGE" -> LauncherConfig.LaserConfig.DEFAULT_MIN_PORT_RANGE.toString,
//        "MAX_PORT_RANGE" -> LauncherConfig.LaserConfig.DEFAULT_MAX_PORT_RANGE.toString
//      )
    }.pendingUntilFixed

    val emptyDbConfig = GlobalConfig().withDb(GlobalDBConfig(
      hostname = "", port = 0, username = "", password = "", prefix = ""
    ))

    "request appropriate host port for database" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val data = gtf.getMarathonPayload(DATA(0), emptyDbConfig)
      data.container.docker must beSome
      val Some(docker) = data.container.docker
      docker.portMappings.get must haveSize(1)
      val Some(Seq(pd)) = docker.portMappings
      pd.hostPort must beSome(5432)
    }

    "request appropriate host port for rabbit" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]
      val data = gtf.getMarathonPayload(RABBIT, emptyDbConfig)
      data.container.docker must beSome
      val Some(docker) = data.container.docker
      docker.portMappings.get must haveSize(2)
      val Some(Seq(pd1,_)) = docker.portMappings
      pd1.hostPort must beSome(5672)
    }

    "set database container residency and grace period along with persistent storage" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]
      val data = gtf.getMarathonPayload(DATA(0), emptyDbConfig)
      data.residency must beSome(Residency(Residency.WAIT_FOREVER))
      data.taskKillGracePeriodSeconds must beSome(LauncherConfig.DatabaseConfig.DEFAULT_KILL_GRACE_PERIOD)
      data.container.volumes must beSome(containTheSameElementsAs(
        Seq(Volume("pgdata", "RW", Some(VolumePersistence(config.database.provisionedSize))))
      ))
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
          .configure(
            "database.pgrepl-token" -> "thetoken"
          )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val pay0 = gtf.getMarathonPayload(DATA(0), testGlobalVars)
      val pay1 = gtf.getMarathonPayload(DATA(1), testGlobalVars)
      pay0.env must haveKey("PGREPL_TOKEN")
      pay1.env must haveKey("PGREPL_TOKEN")
      pay0.env.get("PGREPL_TOKEN") must_== pay1.env.get("PGREPL_TOKEN")
      pay0.env.get("PGREPL_TOKEN") must beSome("thetoken")
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
        "PGREPL_MASTER_IP" -> "data-0.gestalt.marathon.mesos",
        "PGREPL_MASTER_PORT" -> "5432"
      )
      val p1 = gtf.getMarathonPayload(DATA(1), testGlobalVars)
      val p2 = gtf.getMarathonPayload(DATA(2), testGlobalVars)
      val p3 = gtf.getMarathonPayload(DATA(3), testGlobalVars)
      val p10 = gtf.getMarathonPayload(DATA(10), testGlobalVars)
      p1.env must havePairs(standbyvars:_*)
      p2.env must havePairs(standbyvars:_*)
      p3.env must havePairs(standbyvars:_*)
      p10.env must havePairs(standbyvars:_*)
      Json.toJson(p1).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p2).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p3).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p10).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
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
