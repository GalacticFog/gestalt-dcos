import com.galacticfog.gestalt.dcos.{AppSpec, BuildInfo, GestaltTaskFactory}
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._

class TaskFactorySpec extends Specification {

  "GestaltTaskFactory" should {

    "allow config to override docker images for service containers" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "containers.rabbit" -> "test-rabbit:tag",
          "containers.kong" -> "test-kong:tag",
          "containers.data" -> "test-data:tag",
          "containers.security" -> "test-security:tag",
          "containers.meta" -> "test-meta:tag",
          "containers.policy" -> "test-policy:tag",
          "containers.laser" -> "test-laser:tag",
          "containers.api-gateway" -> "test-api-gateway:tag",
          "containers.api-proxy" -> "test-api-proxy:tag",
          "containers.ui" -> "test-ui:tag",
          "containers.laser-executor-js"     -> "test-js-executor:tag",
          "containers.laser-executor-jvm"    -> "test-jvm-executor:tag",
          "containers.laser-executor-dotnet" -> "test-dotnet-executor:tag",
          "containers.laser-executor-golang" -> "test-golang-executor:tag",
          "containers.laser-executor-python" -> "test-python-executor:tag",
          "containers.laser-executor-ruby"   -> "test-ruby-executor:tag"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec(DATA(0), globals) must haveImage("test-data:tag")
      gtf.getAppSpec(DATA(1), globals) must haveImage("test-data:tag")
      gtf.getAppSpec(RABBIT, globals) must haveImage("test-rabbit:tag")
      gtf.getAppSpec(KONG, globals) must haveImage("test-kong:tag")
      gtf.getAppSpec(SECURITY, globals) must haveImage("test-security:tag")
      gtf.getAppSpec(META, globals) must haveImage("test-meta:tag")
      gtf.getAppSpec(POLICY, globals) must haveImage("test-policy:tag")
      gtf.getAppSpec(API_GATEWAY, globals) must haveImage("test-api-gateway:tag")
      gtf.getAppSpec(API_PROXY, globals) must haveImage("test-api-proxy:tag")
      gtf.getAppSpec(UI, globals) must haveImage("test-ui:tag")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage("test-laser:tag")
      laser must haveEnvVar("EXECUTOR_0_IMAGE" -> "test-js-executor:tag")
      laser must haveEnvVar("EXECUTOR_1_IMAGE" -> "test-jvm-executor:tag")
      laser must haveEnvVar("EXECUTOR_2_IMAGE" -> "test-dotnet-executor:tag")
      laser must haveEnvVar("EXECUTOR_3_IMAGE" -> "test-python-executor:tag")
      laser must haveEnvVar("EXECUTOR_4_IMAGE" -> "test-ruby-executor:tag")
      laser must haveEnvVar("EXECUTOR_5_IMAGE" -> "test-golang-executor:tag")
    }

    "support ensemble versioning via config" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "gestalt-framework-version" -> "9.10.11.12"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec(DATA(0), globals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(DATA(1), globals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(RABBIT, globals) must haveImage("galacticfog/rabbit:dcos-9.10.11.12")
      gtf.getAppSpec(KONG, globals) must haveImage("galacticfog/kong:dcos-9.10.11.12")
      gtf.getAppSpec(SECURITY, globals) must haveImage("galacticfog/gestalt-security:dcos-9.10.11.12")
      gtf.getAppSpec(META, globals) must haveImage("galacticfog/gestalt-meta:dcos-9.10.11.12")
      gtf.getAppSpec(POLICY, globals) must haveImage("galacticfog/gestalt-policy:dcos-9.10.11.12")
      gtf.getAppSpec(API_GATEWAY, globals) must haveImage("galacticfog/gestalt-api-gateway:dcos-9.10.11.12")
      gtf.getAppSpec(API_PROXY, globals) must haveImage("galacticfog/gestalt-api-proxy:dcos-9.10.11.12")
      gtf.getAppSpec(UI, globals) must haveImage("galacticfog/gestalt-ui:dcos-9.10.11.12")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage("galacticfog/gestalt-laser:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_0_IMAGE" -> "galacticfog/gestalt-laser-executor-js:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_1_IMAGE" -> "galacticfog/gestalt-laser-executor-jvm:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_2_IMAGE" -> "galacticfog/gestalt-laser-executor-dotnet:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_3_IMAGE" -> "galacticfog/gestalt-laser-executor-python:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_4_IMAGE" -> "galacticfog/gestalt-laser-executor-ruby:dcos-9.10.11.12")
      laser must haveEnvVar("EXECUTOR_5_IMAGE" -> "galacticfog/gestalt-laser-executor-golang:dcos-9.10.11.12")
    }

    "default to current version" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      val ver = BuildInfo.version

      gtf.getAppSpec(DATA(0), globals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(DATA(1), globals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(RABBIT, globals)       must haveImage(s"galacticfog/rabbit:dcos-${ver}")
      gtf.getAppSpec(KONG, globals)         must haveImage(s"galacticfog/kong:dcos-${ver}")
      gtf.getAppSpec(SECURITY, globals)     must haveImage(s"galacticfog/gestalt-security:dcos-${ver}")
      gtf.getAppSpec(META, globals)         must haveImage(s"galacticfog/gestalt-meta:dcos-${ver}")
      gtf.getAppSpec(POLICY, globals)       must haveImage(s"galacticfog/gestalt-policy:dcos-${ver}")
      gtf.getAppSpec(API_GATEWAY, globals)  must haveImage(s"galacticfog/gestalt-api-gateway:dcos-${ver}")
      gtf.getAppSpec(API_PROXY, globals)    must haveImage(s"galacticfog/gestalt-api-proxy:dcos-${ver}")
      gtf.getAppSpec(UI, globals)           must haveImage(s"galacticfog/gestalt-ui:dcos-${ver}")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage(s"galacticfog/gestalt-laser:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_0_IMAGE" -> s"galacticfog/gestalt-laser-executor-js:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_1_IMAGE" -> s"galacticfog/gestalt-laser-executor-jvm:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_2_IMAGE" -> s"galacticfog/gestalt-laser-executor-dotnet:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_3_IMAGE" -> s"galacticfog/gestalt-laser-executor-python:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_4_IMAGE" -> s"galacticfog/gestalt-laser-executor-ruby:dcos-${ver}")
      laser must haveEnvVar("EXECUTOR_5_IMAGE" -> s"galacticfog/gestalt-laser-executor-golang:dcos-${ver}")
    }

  }

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

}
