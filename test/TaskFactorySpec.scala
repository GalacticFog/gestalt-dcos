import com.galacticfog.gestalt.dcos.{AppSpec, GestaltTaskFactory}
import modules.Module
import org.specs2.mutable.Specification
import play.api.{Environment, Configuration}
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json

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
          "containers.gestalt-ui" -> "test-ui:tag"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec("data", globals) must haveImage("test-data:tag")
      gtf.getAppSpec("rabbit", globals) must haveImage("test-rabbit:tag")
      gtf.getAppSpec("kong", globals) must haveImage("test-kong:tag")
      gtf.getAppSpec("security", globals) must haveImage("test-security:tag")
      gtf.getAppSpec("meta", globals) must haveImage("test-meta:tag")
      gtf.getAppSpec("policy", globals) must haveImage("test-policy:tag")
      gtf.getAppSpec("lambda", globals) must haveImage("test-lambda:tag")
      gtf.getAppSpec("api-gateway", globals) must haveImage("test-api-gateway:tag")
      gtf.getAppSpec("api-proxy", globals) must haveImage("test-api-proxy:tag")
      gtf.getAppSpec("ui", globals) must haveImage("test-ui:tag")
    }

    "support ensemble versioning" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "gestalt-framework-version" -> "9.10.11.12"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec("data", globals) must haveImage("galacticfog/gestalt-data:dcos-9.10.11.12")
      gtf.getAppSpec("rabbit", globals) must haveImage("galacticfog/rabbit:dcos-9.10.11.12")
      gtf.getAppSpec("kong", globals) must haveImage("galacticfog/kong:dcos-9.10.11.12")
      gtf.getAppSpec("security", globals) must haveImage("galacticfog/gestalt-security:dcos-9.10.11.12")
      gtf.getAppSpec("meta", globals) must haveImage("galacticfog/gestalt-meta:dcos-9.10.11.12")
      gtf.getAppSpec("policy", globals) must haveImage("galacticfog/gestalt-policy:dcos-9.10.11.12")
      gtf.getAppSpec("lambda", globals) must haveImage("galacticfog/gestalt-lambda:dcos-9.10.11.12")
      gtf.getAppSpec("api-gateway", globals) must haveImage("galacticfog/gestalt-api-gateway:dcos-9.10.11.12")
      gtf.getAppSpec("api-proxy", globals) must haveImage("galacticfog/gestalt-api-proxy:dcos-9.10.11.12")
      gtf.getAppSpec("ui", globals) must haveImage("galacticfog/gestalt-ui:dcos-9.10.11.12")
    }

    "default to latest" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val globals = Json.obj()

      gtf.getAppSpec("data", globals) must haveImage("galacticfog/gestalt-data:dcos-latest")
      gtf.getAppSpec("rabbit", globals) must haveImage("galacticfog/rabbit:dcos-latest")
      gtf.getAppSpec("kong", globals) must haveImage("galacticfog/kong:dcos-latest")
      gtf.getAppSpec("security", globals) must haveImage("galacticfog/gestalt-security:dcos-latest")
      gtf.getAppSpec("meta", globals) must haveImage("galacticfog/gestalt-meta:dcos-latest")
      gtf.getAppSpec("policy", globals) must haveImage("galacticfog/gestalt-policy:dcos-latest")
      gtf.getAppSpec("lambda", globals) must haveImage("galacticfog/gestalt-lambda:dcos-latest")
      gtf.getAppSpec("api-gateway", globals) must haveImage("galacticfog/gestalt-api-gateway:dcos-latest")
      gtf.getAppSpec("api-proxy", globals) must haveImage("galacticfog/gestalt-api-proxy:dcos-latest")
      gtf.getAppSpec("ui", globals) must haveImage("galacticfog/gestalt-ui:dcos-latest")
    }

  }

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def env(name: String, default: String): String = {
    scala.util.Properties.envOrElse(name, default)
  }

}
