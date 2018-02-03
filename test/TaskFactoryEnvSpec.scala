import java.util.UUID

import com.galacticfog.gestalt.dcos.LauncherConfig.LaserConfig.LaserRuntime
import com.galacticfog.gestalt.dcos.LauncherConfig.DCOSACSServiceAccountCreds
import com.galacticfog.gestalt.dcos.LauncherConfig.LaserExecutors._
import com.galacticfog.gestalt.dcos._
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.GuiceApplicationBuilder
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import org.specs2.execute.Result
import org.specs2.matcher.JsonMatchers
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.util.Try

class TaskFactoryEnvSpec extends Specification with JsonMatchers with TestingUtils {

  "GestaltTaskFactory" should {

    // this test will be run multiple times by the harness, with and without environment variables
    // it needs to pass independent of the existence of the env vars by falling back on defaults
    "support environment variables for ensemble versioning or override" in {
      val injector = new GuiceApplicationBuilder()
        .configure(
          // these are necessary so that the logging provider can be provisioned
          "logging.es-cluster-name" -> "blah",
          "logging.es-host" -> "blah",
          "logging.es-port-transport" -> "1111",
          "logging.es-port-rest" -> "2222",
          "logging.provision-provider" -> true
        )
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]
      val lc  = injector.instanceOf[LauncherConfig]

      val globals = GlobalConfig()
        .withDb(GlobalDBConfig(
          hostname = "", port = 0, username = "", password = "", prefix = ""
        ))
        .withSec(GlobalSecConfig(
          hostname = "", port = 0, apiKey = "", apiSecret = "", realm = None
        ))
      val apiKey = GestaltAPIKey("", Some(""), UUID.randomUUID(), false)

      def uuid = UUID.randomUUID()

      println("GESTALT_FRAMEWORK_VERSION: " + env("GESTALT_FRAMEWORK_VERSION"))

      val ensVer = env("GESTALT_FRAMEWORK_VERSION") getOrElse BuildInfo.version

      gtf.getAppSpec(DATA(0), globals) must haveImage(env("GESTALT_DATA_IMG").getOrElse(s"galacticfog/postgres_repl:release-${ensVer}"))
      gtf.getAppSpec(DATA(1), globals) must haveImage(env("GESTALT_DATA_IMG").getOrElse(s"galacticfog/postgres_repl:release-${ensVer}"))
      gtf.getAppSpec(RABBIT, globals) must haveImage(env("GESTALT_RABBIT_IMG").getOrElse(s"galacticfog/rabbit:release-${ensVer}"))
      gtf.getAppSpec(SECURITY, globals) must haveImage(env("GESTALT_SECURITY_IMG").getOrElse(s"galacticfog/gestalt-security:release-${ensVer}"))
      gtf.getAppSpec(META, globals) must haveImage(env("GESTALT_META_IMG").getOrElse(s"galacticfog/gestalt-meta:release-${ensVer}"))
      gtf.getAppSpec(UI, globals) must haveImage(env("GESTALT_UI_IMG").getOrElse(s"galacticfog/gestalt-ui-react:release-${ensVer}"))

      gtf.getKongProvider(uuid, uuid) must haveServiceImage(env("GESTALT_KONG_IMG").getOrElse(s"galacticfog/kong:release-${ensVer}"))
      gtf.getPolicyProvider(apiKey, uuid, uuid, uuid) must haveServiceImage(env("GESTALT_POLICY_IMG").getOrElse(s"galacticfog/gestalt-policy:release-${ensVer}"))
      gtf.getLaserProvider(apiKey, uuid, uuid, uuid, uuid, Seq.empty, uuid) must haveServiceImage(env("GESTALT_LASER_IMG").getOrElse(s"galacticfog/gestalt-laser:release-${ensVer}"))
      gtf.getLogProvider(uuid) must beSome(haveServiceImage(env("GESTALT_LOG_IMG").getOrElse(s"galacticfog/gestalt-log:release-${ensVer}")))
      gtf.getGatewayProvider(uuid, uuid, uuid, uuid) must haveServiceImage(env("GESTALT_API_GATEWAY_IMG").getOrElse(s"galacticfog/gestalt-api-gateway:release-${ensVer}"))

      def getImage(lr: LaserRuntime) = lr.name match {
        case "nashorn-executor" => env("LASER_EXECUTOR_JS_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-js:release-${ensVer}")
        case "nodejs-executor"  => env("LASER_EXECUTOR_NODEJS_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-nodejs:release-${ensVer}")
        case "jvm-executor"     => env("LASER_EXECUTOR_JVM_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-jvm:release-${ensVer}")
        case "dotnet-executor"  => env("LASER_EXECUTOR_DOTNET_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-dotnet:release-${ensVer}")
        case "python-executor"  => env("LASER_EXECUTOR_PYTHON_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-python:release-${ensVer}")
        case "ruby-executor"    => env("LASER_EXECUTOR_RUBY_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-ruby:release-${ensVer}")
        case "golang-executor"  => env("LASER_EXECUTOR_GOLANG_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-golang:release-${ensVer}")
        case _ => throw new RuntimeException("unexpected")
      }

      Result.foreach(lc.laser.enabledRuntimes) {
        lr => gtf.getExecutorProvider(lr) must haveLaserRuntimeImage(getImage(lr))
      }
    }

  }

  "LauncherConfig" should {

    "properly parse acs credentials from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]

      val expectedConfig = (for {
        method <- sys.env.get("DCOS_AUTH_METHOD")
        if method == "acs"
        creds <- sys.env.get("DCOS_ACS_SERVICE_ACCT_CREDS")
      } yield Json.parse(creds).as[DCOSACSServiceAccountCreds])

      lc.dcosAuth must_== expectedConfig
    }

    "properly parse laser config from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]

      val maybeMaxConn  = sys.env.get("LASER_MAX_CONN_TIME").map(_.toInt)
      val maybeHBTimeout  = sys.env.get("LASER_EXECUTOR_HEARTBEAT_TIMEOUT").map(_.toInt)
      val maybeHBPeriod  = sys.env.get("LASER_EXECUTOR_HEARTBEAT_PERIOD").map(_.toInt)
      val maybeHostOx = sys.env.get("LASER_SERVICE_HOST_OVERRIDE")
      val maybePortOx = sys.env.get("LASER_SERVICE_PORT_OVERRIDE").map(_.toInt)
      val maybeScaleDown = sys.env.get("LASER_SCALE_DOWN_TIMEOUT").map(_.toInt)
      lc.laser.maxCoolConnectionTime    must_== maybeMaxConn
      lc.laser.executorHeartbeatTimeout must_== maybeHBTimeout
      lc.laser.executorHeartbeatPeriod  must_== maybeHBPeriod
      lc.laser.serviceHostOverride      must_== maybeHostOx
      lc.laser.servicePortOverride      must_== maybePortOx
      lc.laser.scaleDownTimeout         must_== maybeScaleDown
    }

    "properly pass-through legacy laser environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val laserPayload = gtf.getLaserProvider(GestaltAPIKey("",Some(""),uuid,false), uuid, uuid, uuid, uuid, Seq.empty, uuid)

      // should work with any env var starting with LASER_, but these are the important legacy ones
      Result.foreach( Seq(
        "DEFAULT_EXECUTOR_CPU",
        "DEFAULT_EXECUTOR_RAM",
        "ADVERTISE_HOSTNAME",
        "ETHERNET_PORT",
        "MIN_COOL_EXECUTORS") ) {
        name =>
          val env = sys.env.get("LASER_" + name)
          env must_== launcherConfig.extraEnv.get(LASER).flatMap(_.get(name))
          val prv = (laserPayload \ "properties" \ "config" \ "env" \ "private" \ name).asOpt[String]
          prv must_== env
      }
    }

    "properly capture all extra env vars" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]

      Result.foreach(Seq(
        RABBIT -> "RABBIT",
        DATA(0) -> "DATA_0",
        DATA(1) -> "DATA_1",
        DATA(2) -> "DATA_2",
        SECURITY -> "SECURITY",
        META -> "META",
        UI -> "UI",
        KONG -> "KONG",
        LASER -> "LASER",
        POLICY -> "POLICY",
        API_GATEWAY -> "API_GATEWAY",
        LOG -> "LOG",
        EXECUTOR_DOTNET  -> "EXECUTOR_DOTNET",
        EXECUTOR_NASHORN -> "EXECUTOR_NASHORN",
        EXECUTOR_NODEJS  -> "EXECUTOR_NODEJS",
        EXECUTOR_JVM     -> "EXECUTOR_JVM",
        EXECUTOR_PYTHON  -> "EXECUTOR_PYTHON",
        EXECUTOR_GOLANG  -> "EXECUTOR_GOLANG",
        EXECUTOR_RUBY    -> "EXECUTOR_RUBY"
      )) { case (svc,prefix) =>
        val extra = launcherConfig.extraEnv(svc)
        val found = sys.env.collect({
          case (k,v) if k.startsWith(prefix + "_") && !LauncherConfig.wellKnownEnvVars.contains(k) => k.stripPrefix(prefix + "_") -> v
        })
        extra must havePairs(found.toSeq:_*)
      }
    }

    "properly capture all CPU allocation env vars" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]

      Result.foreach(Seq(
        RABBIT -> "RABBIT",
        DATA(0) -> "DATA_0",
        DATA(1) -> "DATA_1",
        DATA(2) -> "DATA_2",
        SECURITY -> "SECURITY",
        META -> "META",
        UI -> "UI",
        KONG -> "KONG",
        LASER -> "LASER",
        POLICY -> "POLICY",
        API_GATEWAY -> "API_GATEWAY",
        LOG -> "LOG"
      )) { case (svc,prefix) =>
        val cpu = launcherConfig.resources.cpu.get(svc)
        val found = sys.env.get(s"CPU_$prefix").map(_.toDouble)
        cpu must_== found
      }
    }

    "properly capture all MEM allocation env vars" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]

      Result.foreach(Seq(
        RABBIT -> "RABBIT",
        DATA(0) -> "DATA_0",
        DATA(1) -> "DATA_1",
        DATA(2) -> "DATA_2",
        SECURITY -> "SECURITY",
        META -> "META",
        UI -> "UI",
        KONG -> "KONG",
        LASER -> "LASER",
        POLICY -> "POLICY",
        API_GATEWAY -> "API_GATEWAY",
        LOG -> "LOG"
      )) { case (svc,prefix) =>
        val cpu = launcherConfig.resources.mem.get(svc)
        val found = sys.env.get(s"MEM_$prefix").map(_.toInt)
        cpu must_== found
      }
    }

    "properly pass-through all extraEnv vars as env vars on base services" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val globals = GlobalConfig()
        .withDb(GlobalDBConfig(
          hostname = "", port = 0, username = "", password = "", prefix = ""
        ))
        .withSec(GlobalSecConfig(
          hostname = "", port = 0, apiKey = "", apiSecret = "", realm = None
        ))

      Result.foreach( Seq(
        RABBIT,
        DATA(0),
        DATA(1),
        DATA(2),
        SECURITY,
        META,
        UI
      )) {
        svc =>
          val extra = launcherConfig.extraEnv(svc)
          val payload = gtf.getAppSpec(svc, globals)
          payload.env must havePairs(extra.toSeq:_*)
      }
    }

    "properly pass-through all extraEnv vars as env vars on provider services" in {
      val injector = new GuiceApplicationBuilder()
        .configure(
          "logging.es-cluster-name" -> "blah",
          "logging.es-host" -> "blah",
          "logging.es-port-transport" -> "1111",
          "logging.es-port-rest" -> "2222",
          "logging.provision-provider" -> true
        )
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val key = GestaltAPIKey("", Some(""), uuid, false)

      // should work with any env var starting with LASER_, but these are the important legacy ones
      Result.foreach( Seq(
        KONG,
        LASER,
        POLICY,
        API_GATEWAY,
        LOG )) {
        prv =>
          val payload = prv match {
            case KONG => gtf.getKongProvider(uuid, uuid)
            case LASER => gtf.getLaserProvider(key, uuid, uuid, uuid, uuid, Seq.empty, uuid)
            case POLICY => gtf.getPolicyProvider(key, uuid, uuid, uuid)
            case API_GATEWAY => gtf.getGatewayProvider(uuid, uuid, uuid, uuid)
            case LOG => gtf.getLogProvider(uuid).get
            case _ => throw new RuntimeException("this was not expected")
          }

          val extra = launcherConfig.extraEnv(prv)
          val env = (payload \ "properties" \ "config" \ "env" \ "private").asOpt[Map[String,String]].getOrElse(Map.empty)
          env must havePairs(extra.toSeq:_*)
      }
    }

    "properly pass-through all extraEnv vars as env vars on executor providers" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]

      Result.foreach(LauncherConfig.LaserConfig.KNOWN_LASER_RUNTIMES.toSeq) {
        ep =>
          val extra = launcherConfig.extraEnv(ep._1)
          val payload = gtf.getExecutorProvider(ep._2)
          val env = (payload \ "properties" \ "config" \ "env" \ "public").asOpt[Map[String,String]].getOrElse(Map.empty)
          env must havePairs(extra.toSeq:_*)
      }
    }

    "properly provision resources for on base services" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val globals = GlobalConfig()
        .withDb(GlobalDBConfig(
          hostname = "", port = 0, username = "", password = "", prefix = ""
        ))
        .withSec(GlobalSecConfig(
          hostname = "", port = 0, apiKey = "", apiSecret = "", realm = None
        ))

      Result.foreach( Seq(
        RABBIT,
        DATA(0),
        DATA(1),
        DATA(2),
        SECURITY,
        META,
        UI
      )) {
        svc =>
          val cpu = launcherConfig.resources.cpu.get(svc)
          val mem = launcherConfig.resources.mem.get(svc)
          val payload = gtf.getAppSpec(svc, globals)
          cpu must beNone or beSome(payload.cpus)
          mem must beNone or beSome(payload.mem)
      }
    }

    "properly pass-through all extraEnv vars as env vars on provider services" in {
      val injector = new GuiceApplicationBuilder()
        .configure(
          "logging.es-cluster-name" -> "blah",
          "logging.es-host" -> "blah",
          "logging.es-port-transport" -> "1111",
          "logging.es-port-rest" -> "2222",
          "logging.provision-provider" -> true
        )
        .disable[Module]
        .injector
      val launcherConfig = injector.instanceOf[LauncherConfig]
      val gtf = injector.instanceOf[GestaltTaskFactory]
      val key = GestaltAPIKey("", Some(""), uuid, false)

      // should work with any env var starting with LASER_, but these are the important legacy ones
      Result.foreach( Seq(
        KONG,
        LASER,
        POLICY,
        API_GATEWAY,
        LOG )) {
        prv =>
          val payload = prv match {
            case KONG => gtf.getKongProvider(uuid, uuid)
            case LASER => gtf.getLaserProvider(key, uuid, uuid, uuid, uuid, Seq.empty, uuid)
            case POLICY => gtf.getPolicyProvider(key, uuid, uuid, uuid)
            case API_GATEWAY => gtf.getGatewayProvider(uuid, uuid, uuid, uuid)
            case LOG => gtf.getLogProvider(uuid).get
            case _ => throw new RuntimeException("this was not expected")
          }

          val cpu = launcherConfig.resources.cpu.get(prv)
          val mem = launcherConfig.resources.mem.get(prv)
          val container = (payload \ "properties" \ "services" \(0) \ "container_spec" \ "properties").as[JsObject]
          cpu must beNone or beSome((container \ "cpus").as[Double])
          mem must beNone or beSome((container \ "memory").as[Int])
      }
    }

    "properly parse marathon user network from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]

      val maybeNetworkName = sys.env.get("MARATHON_NETWORK_NAME")
      lc.marathon.networkName must_== maybeNetworkName
    }

    "properly parse mesos health check from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]

      val mesosHealthCheck = sys.env.get("MESOS_HEALTH_CHECKS").flatMap(s => Try(s.toBoolean).toOption).getOrElse(false)
      lc.marathon.mesosHealthChecks must_== mesosHealthCheck
    }

    "properly parse caas provider network list from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]

      val maybeNetList = sys.env.get("MARATHON_NETWORK_LIST")
      lc.marathon.networkList must_== maybeNetList
    }

    "properly parse caas provider exposure groups from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val lc  = injector.instanceOf[LauncherConfig]

      val maybeNetList = sys.env.get("MARATHON_HAPROXY_GROUPS")
      lc.marathon.haproxyGroups must_== maybeNetList
    }

    "properly parse marathon-lb url from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]
      val maybeLbUrl = sys.env.get("MARATHON_LB_URL")
      lc.marathon.marathonLbUrl must_== maybeLbUrl
    }

    "properly parse TLD url from environment variables" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val lc  = injector.instanceOf[LauncherConfig]
      val maybeTLD = sys.env.get("MARATHON_TLD")
      lc.marathon.tld must_== maybeTLD
    }

    "properly parse database config from environment variables" in {
      import LauncherConfig.DatabaseConfig
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val lc  = injector.instanceOf[LauncherConfig]

      val dbconfig = DatabaseConfig(
        provision = sys.env.get("DATABASE_PROVISION").map(_.toBoolean).getOrElse(true),
        provisionedSize = sys.env.get("DATABASE_PROVISIONED_SIZE").map(_.toInt).getOrElse(DatabaseConfig.DEFAULT_DISK),
        provisionedCpu = sys.env.get("DATABASE_PROVISIONED_CPU").map(_.toDouble),
        provisionedMemory = sys.env.get("DATABASE_PROVISIONED_MEMORY").map(_.toInt),
        numSecondaries = sys.env.get("DATABASE_NUM_SECONDARIES").map(_.toInt).getOrElse(0),
        pgreplToken = sys.env.get("DATABASE_PGREPL_TOKEN").getOrElse("iw4nn4b3likeu"),
        hostname = sys.env.get("DATABASE_HOSTNAME").getOrElse("gestalt-framework-data"),
        port = sys.env.get("DATABASE_PORT").map(_.toInt).getOrElse(5432),
        username = sys.env.get("DATABASE_USERNAME").getOrElse("gestaltdev"),
        password = sys.env.get("DATABASE_PASSWORD").getOrElse("letmein"),
        prefix = sys.env.get("DATABASE_PREFIX").getOrElse("gestalt-")
      )
      lc.database must_== dbconfig
    }

    "properly parse elasticsearch config from environment variables" in {
      import LauncherConfig.LoggingConfig
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val lc  = injector.instanceOf[LauncherConfig]

      val esconfig = LoggingConfig(
        esClusterName = sys.env.get("LOGGING_ES_CLUSTER_NAME"),
        esHost = sys.env.get("LOGGING_ES_HOST"),
        esPortTransport = sys.env.get("LOGGING_ES_PORT_TRANSPORT").map(_.toInt),
        esPortREST      = sys.env.get("LOGGING_ES_PORT_REST").map(_.toInt),
        esProtocol = sys.env.get("LOGGING_ES_PROTOCOL"),
        provisionProvider = sys.env.get("LOGGING_PROVISION_PROVIDER").map(_.toBoolean).getOrElse(false),
        configureLaser = sys.env.get("LOGGING_CONFIGURE_LASER").map(_.toBoolean).getOrElse(false)
      )
      lc.logging must_== esconfig
    }

  }

  def haveLaserRuntimeImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("public") /("IMAGE" -> name)

  def haveImage(name: => String) = ((_: AppSpec).image) ^^ be_==(name)

  def haveServiceImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("services") */("container_spec") /("properties") /("image" -> name)

  def haveEnvVar(pair: => (String, String)) = ((_: AppSpec).env) ^^ havePair(pair)

  def env(name: String): Option[String] = {
    scala.util.Properties.envOrNone(name)
  }

}
