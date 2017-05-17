import com.galacticfog.gestalt.dcos.{AppSpec, BuildInfo, GestaltTaskFactory, GlobalConfig}
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._

class TaskFactoryEnvSpec extends Specification {

  "GestaltTaskFactory" should {

    // this test will be run multiple times by the harness, with and without environment variables
    // it needs to pass independent of the existence of the env vars by falling back on defaults
    "support environment variables for ensemble versioning or override" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = GlobalConfig()

      println("GESTALT_FRAMEWORK_VERSION: " + env("GESTALT_FRAMEWORK_VERSION"))

      val ensver = env("GESTALT_FRAMEWORK_VERSION") getOrElse BuildInfo.version

      gtf.getAppSpec(DATA(0), globals)     must haveImage(env("GESTALT_DATA_IMG").getOrElse(s"galacticfog/postgres_repl:dcos-${ensver}"))
      gtf.getAppSpec(DATA(1), globals)     must haveImage(env("GESTALT_DATA_IMG").getOrElse(s"galacticfog/postgres_repl:dcos-${ensver}"))
      gtf.getAppSpec(RABBIT, globals)      must haveImage(env("GESTALT_RABBIT_IMG").getOrElse(s"galacticfog/rabbit:dcos-${ensver}"))
      gtf.getAppSpec(SECURITY, globals)    must haveImage(env("GESTALT_SECURITY_IMG").getOrElse(s"galacticfog/gestalt-security:dcos-${ensver}"))
      gtf.getAppSpec(META, globals)        must haveImage(env("GESTALT_META_IMG").getOrElse(s"galacticfog/gestalt-meta:dcos-${ensver}"))
      gtf.getAppSpec(UI, globals)          must haveImage(env("GESTALT_UI_IMG").getOrElse(s"galacticfog/gestalt-ui-react:dcos-${ensver}"))

      ko("update me")
//      gtf.getAppSpec(KONG, globals)        must haveImage(env("GESTALT_KONG_IMG").getOrElse(s"galacticfog/kong:dcos-${ensver}"))
//      gtf.getAppSpec(POLICY, globals)      must haveImage(env("GESTALT_POLICY_IMG").getOrElse(s"galacticfog/gestalt-policy:dcos-${ensver}"))

//      val laserSpec = gtf.getAppSpec(LASER, globals)
//      laserSpec must haveImage(env("GESTALT_LASER_IMG").getOrElse(s"galacticfog/gestalt-laser:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_JS_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-js:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_JVM_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-jvm:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_DOTNET_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-dotnet:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_PYTHON_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-python:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_RUBY_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-ruby:dcos-${ensver}"))
//      laserSpec must haveLaserRuntimeImage(env("LASER_EXECUTOR_GOLANG_IMG").getOrElse(s"galacticfog/gestalt-laser-executor-golang:dcos-${ensver}"))

//      gtf.getAppSpec(API_GATEWAY, globals) must haveImage(env("GESTALT_API_GATEWAY_IMG").getOrElse(s"galacticfog/gestalt-api-gateway:dcos-${ensver}"))
    }.pendingUntilFixed

  }

  def haveLaserRuntimeImage(img: => String) = ((_:AppSpec).env.filterKeys(_.matches("EXECUTOR_[0-9]+_IMAGE")).values) ^^ contain(img)

  def haveImage(name: => String) = ((_: AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

  def env(name: String): Option[String] = {
    scala.util.Properties.envOrNone(name)
  }

}
