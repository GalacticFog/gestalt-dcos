import com.galacticfog.gestalt.dcos._
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.Json
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import org.specs2.execute.Result
import org.specs2.specification.core.Fragment

class TaskFactorySpec extends Specification {

  val testGlobals = GlobalConfig().withDb(GlobalDBConfig(
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
    realm = "192.168.1.50:12345"
  ))

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
          "containers.ui-react" -> "test-react-ui:tag",
          "containers.laser-executor-js"     -> "test-js-executor:tag",
          "containers.laser-executor-jvm"    -> "test-jvm-executor:tag",
          "containers.laser-executor-dotnet" -> "test-dotnet-executor:tag",
          "containers.laser-executor-golang" -> "test-golang-executor:tag",
          "containers.laser-executor-python" -> "test-python-executor:tag",
          "containers.laser-executor-ruby"   -> "test-ruby-executor:tag"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      gtf.getAppSpec(DATA(0), testGlobals) must haveImage("test-data:tag")
      gtf.getAppSpec(DATA(1), testGlobals) must haveImage("test-data:tag")
      gtf.getAppSpec(RABBIT, testGlobals) must haveImage("test-rabbit:tag")
      gtf.getAppSpec(SECURITY, testGlobals) must haveImage("test-security:tag")
      gtf.getAppSpec(META, testGlobals) must haveImage("test-meta:tag")
      gtf.getAppSpec(UI, testGlobals) must haveImage("test-react-ui:tag")
      ko("update me")
//      gtf.getAppSpec(KONG, testGlobals) must haveImage("test-kong:tag")
//      gtf.getAppSpec(POLICY, testGlobals) must haveImage("test-policy:tag")
//      gtf.getAppSpec(API_GATEWAY, testGlobals) must haveImage("test-api-gateway:tag")
//      val laser = gtf.getAppSpec(LASER, testGlobals)
//      laser must haveImage("test-laser:tag")
//      laser must haveLaserRuntimeImage("test-js-executor:tag")
//      laser must haveLaserRuntimeImage("test-jvm-executor:tag")
//      laser must haveLaserRuntimeImage("test-dotnet-executor:tag")
//      laser must haveLaserRuntimeImage("test-python-executor:tag")
//      laser must haveLaserRuntimeImage("test-ruby-executor:tag")
//      laser must haveLaserRuntimeImage("test-golang-executor:tag")
    }.pendingUntilFixed

    "support ensemble versioning via config" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "gestalt-framework-version" -> "9.10.11.12"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      gtf.getAppSpec(DATA(0), testGlobals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(DATA(1), testGlobals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(RABBIT, testGlobals) must haveImage("galacticfog/rabbit:dcos-9.10.11.12")
      gtf.getAppSpec(SECURITY, testGlobals) must haveImage("galacticfog/gestalt-security:dcos-9.10.11.12")
      gtf.getAppSpec(META, testGlobals) must haveImage("galacticfog/gestalt-meta:dcos-9.10.11.12")
      gtf.getAppSpec(UI, testGlobals) must haveImage("galacticfog/gestalt-ui-react:dcos-9.10.11.12")
      ko("update me")
//      gtf.getAppSpec(KONG, testGlobals) must haveImage("galacticfog/kong:dcos-9.10.11.12")
//      gtf.getAppSpec(POLICY, testGlobals) must haveImage("galacticfog/gestalt-policy:dcos-9.10.11.12")
//      gtf.getAppSpec(API_GATEWAY, testGlobals) must haveImage("galacticfog/gestalt-api-gateway:dcos-9.10.11.12")
//      val laser = gtf.getAppSpec(LASER, testGlobals)
//      laser must haveImage("galacticfog/gestalt-laser:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-js:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-jvm:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-dotnet:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-python:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-ruby:dcos-9.10.11.12")
//      laser must haveLaserRuntimeImage("galacticfog/gestalt-laser-executor-golang:dcos-9.10.11.12")
    }.pendingUntilFixed

    "default to current version" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]

      val ver = BuildInfo.version

      gtf.getAppSpec(DATA(0), testGlobals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(DATA(1), testGlobals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(RABBIT, testGlobals)       must haveImage(s"galacticfog/rabbit:dcos-${ver}")
      gtf.getAppSpec(SECURITY, testGlobals)     must haveImage(s"galacticfog/gestalt-security:dcos-${ver}")
      gtf.getAppSpec(META, testGlobals)         must haveImage(s"galacticfog/gestalt-meta:dcos-${ver}")
      gtf.getAppSpec(UI, testGlobals)           must haveImage(s"galacticfog/gestalt-ui-react:dcos-${ver}")
      ko("update me")
//      gtf.getAppSpec(KONG, testGlobals)         must haveImage(s"galacticfog/kong:dcos-${ver}")
//      gtf.getAppSpec(POLICY, testGlobals)       must haveImage(s"galacticfog/gestalt-policy:dcos-${ver}")
//      gtf.getAppSpec(API_GATEWAY, testGlobals)  must haveImage(s"galacticfog/gestalt-api-gateway:dcos-${ver}")
//      val laser = gtf.getAppSpec(LASER, testGlobals)
//      laser must haveImage(s"galacticfog/gestalt-laser:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-js:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-jvm:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-dotnet:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-python:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-ruby:dcos-${ver}")
//      laser must haveLaserRuntimeImage(s"galacticfog/gestalt-laser-executor-golang:dcos-${ver}")
    }.pendingUntilFixed

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
      ko("update me")
//      Fragment.foreach(runtimeNames.keys.toSeq) { runtime =>
//        val injector = new GuiceApplicationBuilder()
//          .disable[Module]
//          .configure("laser.enable-" + runtime + "-runtime" -> false)
//          .injector
//        val gtf = injector.instanceOf[GestaltTaskFactory]
//        val laser = gtf.getAppSpec(LASER, testGlobals)
//        runtime ! {
//          val theseNames = runtimeNames(runtime)
//          val missingDisabled = Result.foreach(theseNames) {
//            name => laser must not be haveLaserRuntime(name)
//          }
//          val presentEnabled = Result.foreach(allNames.diff(theseNames)) {
//            name => laser must haveLaserRuntime(name)
//          }
//          missingDisabled and presentEnabled
//        }
//      }
    }.pendingUntilFixed

    "only provision marathon health checks" in {
      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector
      val gtf = injector.instanceOf[GestaltTaskFactory]
      Result.foreach(Seq(
        RABBIT, SECURITY, META, UI, DATA(0), DATA(1)
      )) {
        svc => gtf.getAppSpec(svc, testGlobals).healthChecks must contain(marathonHealthChecks)
      }
    }

  }

  def marathonHealthChecks = ((_:HealthCheck).protocol.toString) ^^ beOneOf("COMMAND", "HTTPS", "HTTP", "TCP")

  def haveLaserRuntime(name: => String) = ((_:AppSpec).env.filterKeys(_.matches("EXECUTOR_[0-9]+_RUNTIME")).values.flatMap(_.split(";"))) ^^ contain(name)

  def haveLaserRuntimeImage(img: => String) = ((_:AppSpec).env.filterKeys(_.matches("EXECUTOR_[0-9]+_IMAGE")).values) ^^ contain(img)

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

}
