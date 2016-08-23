import com.galacticfog.gestalt.dcos.{AppSpec, GestaltTaskFactory}
import modules.Module
import org.specs2.mutable.Specification
import play.api.{Environment, Configuration}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json

class TaskFactoryEnvSpec extends Specification {

  "GestaltTaskFactory" should {

    // this test will be run multiple times by the harness, with and without environment variables
    // it needs to pass independent of the existence of the env vars by falling back on defaults
    "support environment variables for ensemble versioning or override" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      println("GESTALT_FRAMEWORK_VERSION: " + env("GESTALT_FRAMEWORK_VERSION"))

      val ensver = env("GESTALT_FRAMEWORK_VERSION") getOrElse "latest"

      if (env("GESTALT_DATA_IMG").isDefined)        gtf.getAppSpec("data", globals)        must haveImage(env("GESTALT_DATA_IMG").get)
      else                                          gtf.getAppSpec("data", globals)        must haveImage(s"galacticfog/gestalt-data:dcos-${ensver}")
      if (env("GESTALT_RABBIT_IMG").isDefined)      gtf.getAppSpec("rabbit", globals)      must haveImage(env("GESTALT_RABBIT_IMG").get)
      else                                          gtf.getAppSpec("rabbit", globals)      must haveImage(s"galacticfog/rabbit:dcos-${ensver}")
      if (env("GESTALT_KONG_IMG").isDefined)        gtf.getAppSpec("kong", globals)        must haveImage(env("GESTALT_KONG_IMG").get)
      else                                          gtf.getAppSpec("kong", globals)        must haveImage(s"galacticfog/kong:dcos-${ensver}")
      if (env("GESTALT_SECURITY_IMG").isDefined)    gtf.getAppSpec("security", globals)    must haveImage(env("GESTALT_SECURITY_IMG").get)
      else                                          gtf.getAppSpec("security", globals)    must haveImage(s"galacticfog/gestalt-security:dcos-${ensver}")
      if (env("GESTALT_META_IMG").isDefined)        gtf.getAppSpec("meta", globals)        must haveImage(env("GESTALT_META_IMG").get)
      else                                          gtf.getAppSpec("meta", globals)        must haveImage(s"galacticfog/gestalt-meta:dcos-${ensver}")
      if (env("GESTALT_POLICY_IMG").isDefined)      gtf.getAppSpec("policy", globals)      must haveImage(env("GESTALT_POLICY_IMG").get)
      else                                          gtf.getAppSpec("policy", globals)      must haveImage(s"galacticfog/gestalt-policy:dcos-${ensver}")
      val lambdaSpec = gtf.getAppSpec("lambda", globals)
      if (env("GESTALT_LAMBDA_IMG").isDefined)      lambdaSpec must haveImage(env("GESTALT_LAMBDA_IMG").get)
      else                                          lambdaSpec must haveImage(s"galacticfog/gestalt-lambda:dcos-${ensver}")
      lambdaSpec must haveEnvVar("JS_EXECUTOR"     -> env("LAMBDA_JAVASCRIPT_EXECUTOR_IMG").getOrElse(s"galacticfog/lambda-javascript-executor:dcos-${ensver}"))
      lambdaSpec must haveEnvVar("JAVA_EXECUTOR"   -> env("LAMBDA_JAVA_EXECUTOR_IMG").getOrElse(s"galacticfog/lambda-java-executor:dcos-${ensver}"))
      lambdaSpec must haveEnvVar("DOTNET_EXECUTOR" -> env("LAMBDA_DOTNET_EXECUTOR_IMG").getOrElse(s"galacticfog/lambda-dotnet-executor:dcos-${ensver}"))
      if (env("GESTALT_API_GATEWAY_IMG").isDefined) gtf.getAppSpec("api-gateway", globals) must haveImage(env("GESTALT_API_GATEWAY_IMG").get)
      else                                          gtf.getAppSpec("api-gateway", globals) must haveImage(s"galacticfog/gestalt-api-gateway:dcos-${ensver}")
      if (env("GESTALT_API_PROXY_IMG").isDefined)   gtf.getAppSpec("api-proxy", globals)   must haveImage(env("GESTALT_API_PROXY_IMG").get)
      else                                          gtf.getAppSpec("api-proxy", globals)   must haveImage(s"galacticfog/gestalt-api-proxy:dcos-${ensver}")
      if (env("GESTALT_UI_IMG").isDefined)          gtf.getAppSpec("ui", globals)          must haveImage(env("GESTALT_UI_IMG").get)
      else                                          gtf.getAppSpec("ui", globals)          must haveImage(s"galacticfog/gestalt-ui:dcos-${ensver}")
    }

  }

  def haveImage(name: => String) = ((_: AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

  def env(name: String): Option[String] = {
    scala.util.Properties.envOrNone(name)
  }

}
