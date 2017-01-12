import com.galacticfog.gestalt.dcos.{AppSpec, GestaltTaskFactory}
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
          "containers.gestalt-data" -> "test-data:tag",
          "containers.gestalt-security" -> "test-security:tag",
          "containers.gestalt-meta" -> "test-meta:tag",
          "containers.gestalt-policy" -> "test-policy:tag",
          "containers.gestalt-lambda" -> "test-lambda:tag",
          "containers.gestalt-api-gateway" -> "test-api-gateway:tag",
          "containers.gestalt-api-proxy" -> "test-api-proxy:tag",
          "containers.gestalt-ui" -> "test-ui:tag",
          "containers.lambda-javascript-executor" -> "test-js-executor:tag",
          "containers.lambda-java-executor"       -> "test-java-executor:tag",
          "containers.lambda-dotnet-executor"     -> "test-dotnet-executor:tag"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec(DATA, globals) must haveImage("test-data:tag")
      gtf.getAppSpec(RABBIT, globals) must haveImage("test-rabbit:tag")
      gtf.getAppSpec(KONG, globals) must haveImage("test-kong:tag")
      gtf.getAppSpec(SECURITY, globals) must haveImage("test-security:tag")
      gtf.getAppSpec(META, globals) must haveImage("test-meta:tag")
      gtf.getAppSpec(POLICY, globals) must haveImage("test-policy:tag")
      gtf.getAppSpec(API_GATEWAY, globals) must haveImage("test-api-gateway:tag")
      gtf.getAppSpec(API_PROXY, globals) must haveImage("test-api-proxy:tag")
      gtf.getAppSpec(UI, globals) must haveImage("test-ui:tag")
      val lambda = gtf.getAppSpec(LAMBDA, globals)
      lambda must haveImage("test-lambda:tag")
      lambda must haveEnvVar("JS_EXECUTOR" -> "test-js-executor:tag")
      lambda must haveEnvVar("JAVA_EXECUTOR" -> "test-java-executor:tag")
      lambda must haveEnvVar("DOTNET_EXECUTOR" -> "test-dotnet-executor:tag")
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

      gtf.getAppSpec(DATA, globals) must haveImage("galacticfog/gestalt-data:dcos-9.10.11.12")
      gtf.getAppSpec(RABBIT, globals) must haveImage("galacticfog/rabbit:dcos-9.10.11.12")
      gtf.getAppSpec(KONG, globals) must haveImage("galacticfog/kong:dcos-9.10.11.12")
      gtf.getAppSpec(SECURITY, globals) must haveImage("galacticfog/gestalt-security:dcos-9.10.11.12")
      gtf.getAppSpec(META, globals) must haveImage("galacticfog/gestalt-meta:dcos-9.10.11.12")
      gtf.getAppSpec(POLICY, globals) must haveImage("galacticfog/gestalt-policy:dcos-9.10.11.12")
      gtf.getAppSpec(API_GATEWAY, globals) must haveImage("galacticfog/gestalt-api-gateway:dcos-9.10.11.12")
      gtf.getAppSpec(API_PROXY, globals) must haveImage("galacticfog/gestalt-api-proxy:dcos-9.10.11.12")
      gtf.getAppSpec(UI, globals) must haveImage("galacticfog/gestalt-ui:dcos-9.10.11.12")
      val lambda = gtf.getAppSpec(LAMBDA, globals)
      lambda must haveImage("galacticfog/gestalt-lambda:dcos-9.10.11.12")
      lambda must haveEnvVar("JS_EXECUTOR" -> s"galacticfog/lambda-javascript-executor:dcos-9.10.11.12")
      lambda must haveEnvVar("JAVA_EXECUTOR" -> s"galacticfog/lambda-java-executor:dcos-9.10.11.12")
      lambda must haveEnvVar("DOTNET_EXECUTOR" -> s"galacticfog/lambda-dotnet-executor:dcos-9.10.11.12")
    }

    "default to latest" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec(DATA, globals) must haveImage("galacticfog/gestalt-data:dcos-latest")
      gtf.getAppSpec(RABBIT, globals) must haveImage("galacticfog/rabbit:dcos-latest")
      gtf.getAppSpec(KONG, globals) must haveImage("galacticfog/kong:dcos-latest")
      gtf.getAppSpec(SECURITY, globals) must haveImage("galacticfog/gestalt-security:dcos-latest")
      gtf.getAppSpec(META, globals) must haveImage("galacticfog/gestalt-meta:dcos-latest")
      gtf.getAppSpec(POLICY, globals) must haveImage("galacticfog/gestalt-policy:dcos-latest")
      gtf.getAppSpec(API_GATEWAY, globals) must haveImage("galacticfog/gestalt-api-gateway:dcos-latest")
      gtf.getAppSpec(API_PROXY, globals) must haveImage("galacticfog/gestalt-api-proxy:dcos-latest")
      gtf.getAppSpec(UI, globals) must haveImage("galacticfog/gestalt-ui:dcos-latest")
      val lambda = gtf.getAppSpec(LAMBDA, globals)
      lambda must haveImage("galacticfog/gestalt-lambda:dcos-latest")
      lambda must haveEnvVar("JS_EXECUTOR" -> s"galacticfog/lambda-javascript-executor:dcos-latest")
      lambda must haveEnvVar("JAVA_EXECUTOR" -> s"galacticfog/lambda-java-executor:dcos-latest")
      lambda must haveEnvVar("DOTNET_EXECUTOR" -> s"galacticfog/lambda-dotnet-executor:dcos-latest")
    }

  }

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

}
