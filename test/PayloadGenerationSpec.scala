import java.util.UUID

import com.galacticfog.gestalt.dcos._
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.marathon.MarathonAppPayload.IPPerTaskInfo
import com.galacticfog.gestalt.dcos.marathon._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import modules.Module
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsValue, Json}

class PayloadGenerationSpec extends Specification with JsonMatchers {

  def uuid = UUID.randomUUID()

  def haveEnvVar(pair: => (String, String)) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("private") /(pair)

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

  "Payload generation" should {

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
        id = Some("/gestalt-framework/security"),
        args = Some(Seq("-J-Xmx768m", "-Dhttp.port=9455")),
        env = Some(Json.obj(
          "OAUTH_RATE_LIMITING_PERIOD" -> "1",
          "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
          "DATABASE_HOSTNAME" -> "test-db.marathon.mesos",
          "DATABASE_PORT" -> "5432",
          "DATABASE_NAME" -> "test-security",
          "DATABASE_USERNAME" -> "test-user",
          "DATABASE_PASSWORD" -> "test-password"
        )),
        instances = Some(1),
        cpus = Some(0.5),
        mem = Some(1536),
        disk = Some(0),
        requirePorts = Some(true),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          volumes = None,
          docker = Some(MarathonDockerContainer(
            image = Some("test-security:tag"),
            network = Some("BRIDGE"),
            privileged = Some(false),
            forcePullImage = Some(true),
            portMappings = Some(Seq(
              DockerPortMapping(
                containerPort = Some(9455),
                protocol = Some("tcp"),
                name = Some("http-api"),
                labels = Some(Map("VIP_0" -> "/gestalt-framework-security:9455"))
              ),
              DockerPortMapping(
                containerPort = Some(9455),
                protocol = Some("tcp"),
                name = Some("http-api-dupe"),
                labels = Some(Map())
              )
            ))
          ))
        )),
        labels = Some(Map(
          "HAPROXY_GROUP" -> "external",
          "HAPROXY_0_VHOST" -> "security.galacticfog.com",
          "HAPROXY_1_VHOST" -> "galacticfog.com",
          "HAPROXY_1_PATH" -> "/security",
          "HAPROXY_1_HTTP_BACKEND_PROXYPASS_PATH" -> "/security"
        )),
        healthChecks = Some(Seq(MarathonHealthCheck(
          path = Some("/health"),
          protocol = Some("HTTP"),
          portIndex = Some(0),
          gracePeriodSeconds = Some(300),
          intervalSeconds = Some(30),
          timeoutSeconds = Some(15),
          maxConsecutiveFailures = Some(4)
        ))),
        readinessCheck = Some(MarathonReadinessCheck(
          protocol = Some("HTTP"),
          path = Some("/init"),
          portName = Some("http-api"),
          intervalSeconds = Some(5),
          timeoutSeconds = Some(10)
        )),
        portDefinitions = Some(Seq.empty)
      )
      val security = gtf.getMarathonPayload(SECURITY, globalConfig)
      security must_== expected
    }

    "run database ports/portDefinitions appropriately for USER networking" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.network-name" -> "user-network"
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
      val data0 = gtf.getMarathonPayload(DATA(0), globalConfig)
      data0.ipAddress must beSome(IPPerTaskInfo(networkName = Some("user-network")))
      data0.portDefinitions.get must beEmpty
    }

    "pass ports/portDefinitions appropriately for USER networking" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.network-name" -> "user-network"
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
      val data0 = gtf.getMarathonPayload(DATA(0), globalConfig)
      data0.ipAddress must beSome(IPPerTaskInfo(networkName = Some("user-network")))
      data0.portDefinitions.get must beEmpty
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
      gtf.getMarathonPayload(META, global).env.get.toString must /("GESTALT_SECURITY_REALM" -> realm)
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

    "set ethernet port on laser scheduler if configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "laser.ethernet-port" -> "enp0s8"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      laserPayload must haveEnvVar("ETHERNET_PORT" -> "enp0s8")
    }

    "not set ethernet port on laser scheduler if not configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "laser.ethernet-port" -> "enp0s8"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      (laserPayload \ "properties" \ "config" \ "env" \ "private" \ "ETHERNET_PORT").asOpt[String] must beNone
    }

    "set advertise-host on laser scheduler if configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "laser.advertise-hostname" -> "laser.gestalt.marathon.mesos"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      laserPayload must haveEnvVar("ADVERTISE_HOSTNAME" -> "laser.gestalt.marathon.mesos")
    }

    "not set advertise-host on laser scheduler if not configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "laser.advertise-hostname" -> "laser.gestalt.marathon.mesos"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      (laserPayload \ "properties" \ "config" \ "env" \ "private" \ "ADVERTISE_HOSTNAME").asOpt[String] must beNone
    }

    "set meta-network-name on laser scheduler if configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      laserPayload must haveEnvVar("META_NETWORK_NAME" -> "user-network")
    }

    "fall back to HOST networking on laser scheduler if meta-network-name not configured" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "laser.meta-network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)
      (laserPayload \ "properties" \ "config" \ "env" \ "private" \ "META_NETWORK_NAME").asOpt[String] must beSome("HOST")
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
      data.container.flatMap(_.docker) must beSome
      val Some(docker) = data.container.flatMap(_.docker)
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
      data.container.flatMap(_.docker) must beSome
      val Some(docker) = data.container.flatMap(_.docker)
      docker.portMappings.get must haveSize(2)
      val Some(Seq(pd1,_)) = docker.portMappings
      pd1.hostPort must beSome(5672)
    }

    "not request host port for database in USER networking mode" in {
      val injector = new GuiceApplicationBuilder()
        .configure(
          "marathon.network-name" -> "some-user-network"
        )
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val data = gtf.getMarathonPayload(DATA(0), emptyDbConfig)
      data.container.flatMap(_.docker) must beSome
      val Some(docker) = data.container.flatMap(_.docker)
      docker.portMappings.get must haveSize(1)
      val Some(Seq(pm)) = docker.portMappings
      pm.hostPort must beNone
      data.portDefinitions.get must beEmpty
    }

    "not request host port for rabbit in USER networking mode" in {
      val injector = new GuiceApplicationBuilder()
        .configure(
          "marathon.network-name" -> "some-user-network"
        )
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val data = gtf.getMarathonPayload(RABBIT, emptyDbConfig)
      data.container.flatMap(_.docker) must beSome
      val Some(docker) = data.container.flatMap(_.docker)
      docker.portMappings.get must haveSize(2)
      val Some(Seq(pm1,_)) = docker.portMappings
      pm1.hostPort must beNone
      data.portDefinitions.get must beEmpty
    }

    "set database container residency and grace period along with persistent storage" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]
      val data = gtf.getMarathonPayload(DATA(0), emptyDbConfig)
      data.residency must beSome(Residency(Some(Residency.WAIT_FOREVER)))
      data.taskKillGracePeriodSeconds must beSome(LauncherConfig.DatabaseConfig.DEFAULT_KILL_GRACE_PERIOD)
      data.container.flatMap(_.volumes) must beSome(containTheSameElementsAs(
        Seq(Volume(Some("pgdata"), Some("RW"), Some(VolumePersistence(Some(config.database.provisionedSize)))))
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
      gtf.getMarathonPayload(DATA(0), testGlobalVars).container.flatMap(_.docker) must beSome((d: MarathonDockerContainer) => d.image.get.startsWith("galacticfog/postgres_repl:"))
      gtf.getMarathonPayload(DATA(1), testGlobalVars).container.flatMap(_.docker) must beSome((d: MarathonDockerContainer) => d.image.get.startsWith("galacticfog/postgres_repl:"))
      gtf.getMarathonPayload(DATA(2), testGlobalVars).container.flatMap(_.docker) must beSome((d: MarathonDockerContainer) => d.image.get.startsWith("galacticfog/postgres_repl:"))
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
      payload.env.get.toString must /("PGREPL_ROLE" -> "PRIMARY")
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
      pay0.env.get.toString must /("PGREPL_TOKEN" -> "thetoken")
      pay1.env.get.toString must /("PGREPL_TOKEN" -> "thetoken")
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
      p1.env.get.as[Map[String,String]] must havePairs(standbyvars:_*)
      p2.env.get.as[Map[String,String]] must havePairs(standbyvars:_*)
      p3.env.get.as[Map[String,String]] must havePairs(standbyvars:_*)
      p10.env.get.as[Map[String,String]] must havePairs(standbyvars:_*)
      Json.toJson(p1).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p2).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p3).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
      Json.toJson(p10).toString must /("container") /("docker") /("portMappings") /#(0) /("labels") /("VIP_0" -> "/gestalt-data-secondary:5432")
    }

    "configure meta caas provider with consideration for acceptAnyCertificate == true" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "acceptAnyCertificate" -> true
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      Json.toJson(payload).toString must /("properties") /("config") /("accept_any_cert" -> true)
    }

    "configure meta caas provider with consideration for acceptAnyCertificate == false" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "acceptAnyCertificate" -> false
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      Json.toJson(payload).toString must /("properties") /("config") /("accept_any_cert" -> false)
    }

    "configure meta caas provider with consideration for acceptAnyCertificate missing" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      Json.toJson(payload).toString must /("properties") /("config") /("accept_any_cert" -> false)
    }

    "configure meta caas provider with consideration for acs authentication" in {
      val testServiceId = "meta-dcos-provider"
      val testPrivateKey = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC9OzC0iseKnsqd\nu82KvTav6q+j4MoSS3mGGPZIA2JaD/cMjpzBtaaOxIbcyLWt2M8hwdO3TLXCZiW2\nybz2Koeo3+vNphnO7U4ZggSIuM+RYfhUUnQ79yiYKmL3z93HRrvZBlulG3yOFo5y\n30IFKqyt2QKlPy3ObCtZYwT4opYNnkev/pubtOjsjdkU9/u088eiLfVHwSwpBxjG\n2wbpFVGyN3p55UHW3K6QUrUw8B7EOF2A5EXzgR5GmAgL6SjuzEdghumqdMcSxGoE\n4pL3Y6LHer391ITdxO819o0i3cfglvgXxFGZSsiRVV89X15n8pEbP73cD3sRxnwe\nIwW860ZnAgMBAAECggEAIKUXb+4JIobmWXPOr8KYrpyEFHdxJNrUaifgROggjXz3\nl7j6nghiZXrN8UTG4ujmQuKXTaX0LUdF9lSzPpxzrtSCb4XaKfKSaKAffB614FTQ\nbGuVFcs7u5SEYk//6KLxQS1xnfgx8qk9hd+yGgYUqCEp7awKkPPkPpVwhBw4WrzJ\nkYxJ3bIT7j3svTr5uhno7cFso5jhfFyMA7PruHGNfyOWLIgzgw5qwRUK1WLMyk88\nJivrDRbvuskWK7pxvLrRQ/VA34LvGKLroj9Gqw9HIDGbY526PPjFo/uDq8ErHBsQ\nBdoagN6VihX5YjXdi3eF8mIcaFYBOQj6zB+Kfmkc0QKBgQDjkIemfgpHEMcRsinm\ni0WLlZGD8hjFTNku1Pki5sFffXcHR+FImrEUXL/NqJr8iqIeJ+1cx3OAiBm8PHh4\nl+LYz4H2TlvIEEURmOwLiBbh49N4o7T9the+PluDGLsZ9ka3AGHP1LBcvwYJdf7v\nubK3eky1QQSI5Ce6+uayU76QFQKBgQDU4G4j2eAIVTDQ0xMfJYXFaIh2eVqTkv83\nPeskWhAQcPUKzyX7bPHSdSbx+91ZW5iL8GX4DFp+JBiQFSqNq1tqhLwz9xHTxYrj\nGvi6MUJ4LCOihbU+6JIYuOdxq3govxtnJ+lE4cmwr5Y4HM1wx2dxba9EsItLrzkj\nHGPNDJ6fiwKBgCXgPHO9rsA9TqTnXon8zEp7TokDlpPgQpXE5OKmPbFDFLilgh2v\ngaG9/j6gvYsjF/Ck/KDgoZzXClGGTxbjUOJ9R0hTqnsWGijfpwoUUJqwbNY7iThh\nQnprrpeXWizsDMEQ0zbgU6pcMQkKFrCX2+Ml+/Z/J94Q+3vnntY3khQxAoGAdUkh\n5cbI1E57ktJ4mpSF23n4la3O5bf7vWf0AhdM+oIBwG7ZMmmX4qiBSJnIHs+EgLV2\nuO+1fAJPNjMzOtLKjymKt+bMf607FF1r5Mn3IVbQW17nuT1SISTe/5XFok2Iv5ER\nyM3N3fcgANJ9rkFvEOOpyWKrnItyI5IkunjVfHkCgYEAjmAjQOQt5eCO9kGitL7X\ntQGn8TWWHRCjMm1w3ith7bPp11WrdeyfNuUAB7weQjk2qjAIKTOGWtIRqc36OLPA\nkwF1GDyFXvLqJej/2ZLfytyjhetLAQnRL0qOgCi7EU5+YLXuYnn7zPEJgrR3ogX4\n4rvG4NIQ8wG0sEUTnr06nck=\n-----END PRIVATE KEY-----"
      val testDcosUrl = "https://m1.dcos/acs/api/v1/auth/login"

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "auth.method" -> "acs",
          "auth.acs_service_acct_creds" -> Json.obj(
            "login_endpoint" -> testDcosUrl,
            "uid" -> testServiceId,
            "private_key" -> testPrivateKey,
            "scheme" -> "RS256"
          ).toString
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      (payload \ "properties" \ "config" \ "auth").as[JsObject] must_== Json.obj(
        "scheme" -> "acs",
        "service_account_id" -> testServiceId,
        "private_key" -> testPrivateKey,
        "dcos_base_url" -> testDcosUrl.stripSuffix("/acs/api/v1/auth/login")
      )
    }

    "configure meta caas provider with fallback to basic authentication" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      (Json.toJson(payload) \ "properties" \ "config" \ "auth").as[JsObject] must_== Json.obj(
        "scheme" -> "Basic",
        "username" -> "unused",
        "password" -> "unused"
      )
    }

    "configure meta caas provider with support for marathon-under-marathon and custom dcos cluster name" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.framework-name" -> "marathon-user",
          "marathon.cluster-name" -> "my-dcos-cluster"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      (Json.toJson(payload) \ "properties" \ "config" \ "marathon_framework_name").asOpt[String] must beSome("marathon-user")
      (Json.toJson(payload) \ "properties" \ "config" \ "dcos_cluster_name").asOpt[String] must beSome("my-dcos-cluster")
    }

    "configure meta caas provider marathon-under-marathon and dcos cluster using defaults if not specified" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "marathon.framework-name" -> "marathon-user",
          // "marathon.cluster-name" -> "my-dcos-cluster"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val payload = gtf.getCaasProvider()
      (Json.toJson(payload) \ "properties" \ "config" \ "marathon_framework_name").asOpt[String] must beSome("marathon")
      (Json.toJson(payload) \ "properties" \ "config" \ "dcos_cluster_name").asOpt[String] must beSome("thisdcos")
    }

    "configure base services and payload services to launch with user-specified network if provided" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      def uuid = UUID.randomUUID()

      Fragment.foreach(config.provisionedServices) { svc =>
        val payload = gtf.getMarathonPayload(svc, testGlobalVars)
        svc.name ! {
          payload.ipAddress.flatMap(_.networkName) must beSome("user-network")
          payload.container.flatMap(_.docker).flatMap(_.network) must beSome("USER")
        }
      } ^ br
    }

    "configure base services and payload services to launch appropriately" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "marathon.network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      Fragment.foreach(config.provisionedServices) { svc =>
        val payload = gtf.getMarathonPayload(svc, testGlobalVars)
        svc.name ! {
          payload.ipAddress.flatMap(_.networkName) must beNone
          payload.container.flatMap(_.docker).flatMap(_.network) must beSome("BRIDGE")
          payload.cmd.isDefined must_!= payload.args.isDefined // XOR: Marathon 1.8 requires that exactly one of these must be present
        }
      } ^ br
    }

    "configure payload services to launch with default network in absence of user-specified network" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          // "marathon.network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      val creds = GestaltAPIKey("thekey",Some("sshhh"),uuid,false)
      val laser = gtf.getLaserProvider(creds, uuid, uuid, uuid, uuid, Seq.empty, uuid)
      val gtw   = gtf.getGatewayProvider(uuid, uuid, uuid, uuid)
      val policy = gtf.getPolicyProvider(creds, uuid, uuid, uuid)
      val kong = gtf.getKongProvider(uuid, uuid)
      "gestalt-laser" ! {
        (laser \ "properties" \ "services" \(0) \ "container_spec" \ "properties" \ "network").asOpt[String] must beSome("HOST")
      }
      Fragment.foreach( Seq(
        "gestalt-api-gateway" -> gtw,
        "gestalt-policy" -> policy,
        "kong" -> kong ) ) {
        case (lbl, payload) => lbl ! {
          (payload \ "properties" \ "services" \(0) \ "container_spec" \ "properties" \ "network").asOpt[String] must beSome("BRIDGE")
        }
      } ^ br
    }

    "configure payload services to launch with specified user network" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.network-name" -> "user-network"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      val creds = GestaltAPIKey("thekey",Some("sshhh"),uuid,false)
      val laser = gtf.getLaserProvider(creds, uuid, uuid, uuid, uuid, Seq.empty, uuid)
      val gtw   = gtf.getGatewayProvider(uuid, uuid, uuid, uuid)
      val policy = gtf.getPolicyProvider(creds, uuid, uuid, uuid)
      val kong = gtf.getKongProvider(uuid, uuid)
      Fragment.foreach( Seq(
        "gestalt-laser" -> laser,
        "gestalt-api-gateway" -> gtw,
        "gestalt-policy" -> policy,
        "kong" -> kong ) ) {
        case (lbl, payload) => lbl ! {
          (payload \ "properties" \ "services" \(0) \ "container_spec" \ "properties" \ "network").asOpt[String] must beSome("user-network")
        }
      } ^ br
    }

    "configure payload services to launch with mesos health checks" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "marathon.mesos-health-checks" -> "true"
        )
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val config = injector.instanceOf[LauncherConfig]

      val creds = GestaltAPIKey("thekey",Some("sshhh"),uuid,false)
      val laser = gtf.getLaserProvider(creds, uuid, uuid, uuid, uuid, Seq.empty, uuid)
      val gtw   = gtf.getGatewayProvider(uuid, uuid, uuid, uuid)
      val policy = gtf.getPolicyProvider(creds, uuid, uuid, uuid)
      val kong = gtf.getKongProvider(uuid, uuid)
      Fragment.foreach( Seq(
        "gestalt-laser" -> laser,
        "gestalt-api-gateway" -> gtw,
        "gestalt-policy" -> policy,
        "kong" -> kong ) ) {
        case (lbl, payload) => lbl ! {
          val v = (payload \ "properties" \ "services" \(0) \ "container_spec" \ "properties" \ "health_checks" \\ "protocol").map(_.as[String])
          v must contain(be_==("MESOS_HTTP")).foreach
        }
      } ^ br
    }

  }


}
