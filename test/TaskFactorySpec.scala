import com.galacticfog.gestalt.dcos.{AppSpec, BuildInfo, GestaltTaskFactory, HealthCheck}
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import org.specs2.execute.Result
import org.specs2.specification.core.Fragment

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
      gtf.getAppSpec(UI, globals) must haveImage("test-ui:tag")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage("test-laser:tag")
      laser must haveLaserRuntimeImage("test-js-executor:tag")
      laser must haveLaserRuntimeImage("test-jvm-executor:tag")
      laser must haveLaserRuntimeImage("test-dotnet-executor:tag")
      laser must haveLaserRuntimeImage("test-python-executor:tag")
      laser must haveLaserRuntimeImage("test-ruby-executor:tag")
      laser must haveLaserRuntimeImage("test-golang-executor:tag")
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
      gtf.getAppSpec(UI, globals) must haveImage("galacticfog/gestalt-ui:dcos-9.10.11.12")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage("galacticfog/gestalt-laser:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-js:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-jvm:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-dotnet:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-python:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-ruby:dcos-9.10.11.12")
      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-golang:dcos-9.10.11.12")
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
      gtf.getAppSpec(UI, globals)           must haveImage(s"galacticfog/gestalt-ui:dcos-${ver}")
      val laser = gtf.getAppSpec(LASER, globals)
      laser must haveImage(s"galacticfog/gestalt-laser:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-js:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-jvm:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-dotnet:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-python:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-ruby:dcos-${ver}")
      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-golang:dcos-${ver}")
    }

    "enable and disabled runtimes per command line configuration" in {
      val runtimeNames = Map(
        "js"     -> Seq("nodejs"),
        "jvm"    -> Seq("java", "scala"),
        "dotnet" -> Seq("csharp", "dotnet"),
        "golang" -> Seq("golang"),
        "python" -> Seq("python"),
        "ruby"   -> Seq("ruby")
      )
      val allNames: Seq[String] = runtimeNames.values.toSeq.flatten
      Fragment.foreach(runtimeNames.keys.toSeq) { runtime =>
        val injector = new GuiceApplicationBuilder()
          .disable[Module]
          .configure("laser.enable-" + runtime + "-runtime" -> false)
          .injector
        val gtf = injector.instanceOf[GestaltTaskFactory]
        val laser = gtf.getAppSpec(LASER, Json.obj())
        runtime ! {
          val theseNames = runtimeNames(runtime)
          val missingDisabled = Result.foreach(theseNames) {
            name => laser must not be haveLaserRuntime(name)
          }
          val presentEnabled = Result.foreach(allNames.diff(theseNames)) {
            name => laser must haveLaserRuntime(name)
          }
          missingDisabled and presentEnabled
        }
      }
    }

    "only provision marathon health checks" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      Result.foreach(Seq(
        RABBIT, SECURITY, KONG, API_GATEWAY, LASER, META, UI, POLICY, DATA(0), DATA(1)
      )) {
        svc => gtf.getAppSpec(svc, Json.obj()).healthChecks must contain(marathonHealthChecks)
      }
    }

  }

  def marathonHealthChecks = ((_:HealthCheck).protocol.toString) ^^ beOneOf("COMMAND", "HTTPS", "HTTP", "TCP")

  def haveLaserRuntime(name: => String) = ((_:AppSpec).env.filterKeys(_.matches("EXECUTOR_[0-9]+_RUNTIME")).values.flatMap(_.split(";"))) ^^ contain(name)

  def haveLaserRuntimeImage(img: => String) = ((_:AppSpec).env.filterKeys(_.matches("EXECUTOR_[0-9]+_IMAGE")).values) ^^ contain(img)

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

}
