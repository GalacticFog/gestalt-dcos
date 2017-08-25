import java.util.UUID

import com.galacticfog.gestalt.dcos.LauncherConfig.LaserConfig.LaserRuntime
import com.galacticfog.gestalt.dcos.LauncherConfig.{DCOSACSServiceAccountCreds, Dockerable, WellKnownLaserExecutor}
import com.galacticfog.gestalt.dcos.LauncherConfig.LaserExecutors._
import com.galacticfog.gestalt.dcos._
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.GuiceApplicationBuilder
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import org.specs2.execute.Result
import org.specs2.matcher.JsonMatchers
import play.api.libs.json.{JsValue, Json}

import scala.util.Try

class TaskFactoryEnvSpec extends Specification with JsonMatchers {

  "GestaltTaskFactory" should {

    // this test will be run multiple times by the harness, with and without environment variables
    // it needs to pass independent of the existence of the env vars by falling back on defaults
    "support environment variables for ensemble versioning or override" in {
      val injector = new GuiceApplicationBuilder()
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
      gtf.getGatewayProvider(uuid, uuid, uuid, uuid) must haveServiceImage(env("GESTALT_API_GATEWAY_IMG").getOrElse(s"galacticfog/gestalt-api-gateway:release-${ensVer}"))

      def getImage(lr: LaserRuntime) = lr.name match {
        case "js-executor"     => env("LASER_EXECUTOR_JS_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-js:release-${ensVer}")
        case "jvm-executor"    => env("LASER_EXECUTOR_JVM_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-jvm:release-${ensVer}")
        case "dotnet-executor" => env("LASER_EXECUTOR_DOTNET_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-dotnet:release-${ensVer}")
        case "python-executor" => env("LASER_EXECUTOR_PYTHON_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-python:release-${ensVer}")
        case "ruby-executor"   => env("LASER_EXECUTOR_RUBY_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-ruby:release-${ensVer}")
        case "golang-executor" => env("LASER_EXECUTOR_GOLANG_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-golang:release-${ensVer}")
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

      val maybeAdvertHost  = sys.env.get("LASER_ADVERTISE_HOSTNAME")
      val maybeMaxConn  = sys.env.get("LASER_MAX_CONN_TIME").map(_.toInt)
      val maybeHBTimeout  = sys.env.get("LASER_EXECUTOR_HEARTBEAT_TIMEOUT").map(_.toInt)
      val maybeHBPeriod  = sys.env.get("LASER_EXECUTOR_HEARTBEAT_PERIOD").map(_.toInt)
      lc.laser.advertiseHost  must_== maybeAdvertHost
      lc.laser.maxCoolConnectionTime    must_== maybeMaxConn
      lc.laser.executorHeartbeatTimeout must_== maybeHBTimeout
      lc.laser.executorHeartbeatPeriod  must_== maybeHBPeriod
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

  }

  def haveLaserRuntimeImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("public") /("IMAGE" -> name)

  def haveImage(name: => String) = ((_: AppSpec).image) ^^ be_==(name)

  def haveServiceImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("services") */("container_spec") /("properties") /("image" -> name)

  def haveEnvVar(pair: => (String, String)) = ((_: AppSpec).env) ^^ havePair(pair)

  def env(name: String): Option[String] = {
    scala.util.Properties.envOrNone(name)
  }

}
