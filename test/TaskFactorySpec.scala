import java.util.UUID

import com.galacticfog.gestalt.dcos.LauncherConfig.LaserConfig.LaserRuntime
import com.galacticfog.gestalt.dcos._
import modules.Module
import org.specs2.mutable.Specification
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceInjectorBuilder}
import play.api.libs.json.{JsValue, Json}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import org.specs2.execute.Result
import org.specs2.matcher.JsonMatchers
import org.specs2.specification.core.Fragment

class TaskFactorySpec extends Specification with JsonMatchers {

  def uuid = UUID.randomUUID()

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
    realm = Some("192.168.1.50:12345")
  ))

  val apiKey = GestaltAPIKey("", Some(""), UUID.randomUUID(), false)

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
      val lc  = injector.instanceOf[LauncherConfig]

      gtf.getAppSpec(DATA(0), testGlobals) must haveImage("test-data:tag")
      gtf.getAppSpec(DATA(1), testGlobals) must haveImage("test-data:tag")
      gtf.getAppSpec(RABBIT, testGlobals) must haveImage("test-rabbit:tag")
      gtf.getAppSpec(SECURITY, testGlobals) must haveImage("test-security:tag")
      gtf.getAppSpec(META, testGlobals) must haveImage("test-meta:tag")
      gtf.getAppSpec(UI, testGlobals) must haveImage("test-react-ui:tag")

      gtf.getKongProvider(uuid, uuid) must haveServiceImage("test-kong:tag")
      gtf.getPolicyProvider(apiKey, uuid, uuid, uuid) must haveServiceImage("test-policy:tag")
      gtf.getLaserProvider(apiKey, uuid, uuid, uuid, uuid, Seq.empty, uuid) must haveServiceImage("test-laser:tag")
      gtf.getGatewayProvider(uuid, uuid, uuid, uuid) must haveServiceImage("test-api-gateway:tag")

      def getImage(lr: LaserRuntime) = lr.name match {
        case "js-executor"     => "test-js-executor:tag"
        case "jvm-executor"    => "test-jvm-executor:tag"
        case "dotnet-executor" => "test-dotnet-executor:tag"
        case "python-executor" => "test-python-executor:tag"
        case "ruby-executor"   => "test-ruby-executor:tag"
        case "golang-executor" => "test-golang-executor:tag"
        case _ => throw new RuntimeException("unexpected")
      }

      Result.foreach(lc.laser.enabledRuntimes) {
        lr => gtf.getExecutorProvider(lr) must haveLaserRuntimeImage(getImage(lr))
      }
    }

    "support ensemble versioning via config" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .configure(
          "gestalt-framework-version" -> "9.10.11.12"
        )
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]
      val lc  = injector.instanceOf[LauncherConfig]

      gtf.getAppSpec(DATA(0), testGlobals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(DATA(1), testGlobals) must haveImage("galacticfog/postgres_repl:dcos-9.10.11.12")
      gtf.getAppSpec(RABBIT, testGlobals) must haveImage("galacticfog/rabbit:dcos-9.10.11.12")
      gtf.getAppSpec(SECURITY, testGlobals) must haveImage("galacticfog/gestalt-security:dcos-9.10.11.12")
      gtf.getAppSpec(META, testGlobals) must haveImage("galacticfog/gestalt-meta:dcos-9.10.11.12")
      gtf.getAppSpec(UI, testGlobals) must haveImage("galacticfog/gestalt-ui-react:dcos-9.10.11.12")

      gtf.getKongProvider(uuid, uuid) must haveServiceImage("galacticfog/kong:dcos-9.10.11.12")
      gtf.getPolicyProvider(apiKey, uuid, uuid, uuid) must haveServiceImage("galacticfog/gestalt-policy:dcos-9.10.11.12")
      gtf.getLaserProvider(apiKey, uuid, uuid, uuid, uuid, Seq.empty, uuid) must haveServiceImage("galacticfog/gestalt-laser:dcos-9.10.11.12")
      gtf.getGatewayProvider(uuid, uuid, uuid, uuid) must haveServiceImage("galacticfog/gestalt-api-gateway:dcos-9.10.11.12")

      def getImage(lr: LaserRuntime) = lr.name match {
        case "js-executor"     => "galacticfog/gestalt-laser-executor-js:dcos-9.10.11.12"
        case "jvm-executor"    => "galacticfog/gestalt-laser-executor-jvm:dcos-9.10.11.12"
        case "dotnet-executor" => "galacticfog/gestalt-laser-executor-dotnet:dcos-9.10.11.12"
        case "python-executor" => "galacticfog/gestalt-laser-executor-python:dcos-9.10.11.12"
        case "ruby-executor"   => "galacticfog/gestalt-laser-executor-ruby:dcos-9.10.11.12"
        case "golang-executor" => "galacticfog/gestalt-laser-executor-golang:dcos-9.10.11.12"
        case _ => throw new RuntimeException("unexpected")
      }

      Result.foreach(lc.laser.enabledRuntimes) {
        lr => gtf.getExecutorProvider(lr) must haveLaserRuntimeImage(getImage(lr))
      }
    }

    "default to current version" in {

      val injector = new GuiceApplicationBuilder()
        .disable[Module]
        .injector

      val gtf = injector.instanceOf[GestaltTaskFactory]
      val lc  = injector.instanceOf[LauncherConfig]

      val ver = BuildInfo.version

      gtf.getAppSpec(DATA(0), testGlobals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(DATA(1), testGlobals)      must haveImage(s"galacticfog/postgres_repl:dcos-${ver}")
      gtf.getAppSpec(RABBIT, testGlobals)       must haveImage(s"galacticfog/rabbit:dcos-${ver}")
      gtf.getAppSpec(SECURITY, testGlobals)     must haveImage(s"galacticfog/gestalt-security:dcos-${ver}")
      gtf.getAppSpec(META, testGlobals)         must haveImage(s"galacticfog/gestalt-meta:dcos-${ver}")
      gtf.getAppSpec(UI, testGlobals)           must haveImage(s"galacticfog/gestalt-ui-react:dcos-${ver}")

      gtf.getKongProvider(uuid, uuid) must haveServiceImage(s"galacticfog/kong:dcos-${ver}")
      gtf.getPolicyProvider(apiKey, uuid, uuid, uuid) must haveServiceImage(s"galacticfog/gestalt-policy:dcos-${ver}")
      gtf.getLaserProvider(apiKey, uuid, uuid, uuid, uuid, Seq.empty, uuid) must haveServiceImage(s"galacticfog/gestalt-laser:dcos-${ver}")
      gtf.getGatewayProvider(uuid, uuid, uuid, uuid) must haveServiceImage(s"galacticfog/gestalt-api-gateway:dcos-${ver}")

      def getImage(lr: LaserRuntime) = lr.name match {
        case "js-executor"     => s"galacticfog/gestalt-laser-executor-js:dcos-${ver}"
        case "jvm-executor"    => s"galacticfog/gestalt-laser-executor-jvm:dcos-${ver}"
        case "dotnet-executor" => s"galacticfog/gestalt-laser-executor-dotnet:dcos-${ver}"
        case "python-executor" => s"galacticfog/gestalt-laser-executor-python:dcos-${ver}"
        case "ruby-executor"   => s"galacticfog/gestalt-laser-executor-ruby:dcos-${ver}"
        case "golang-executor" => s"galacticfog/gestalt-laser-executor-golang:dcos-${ver}"
        case _ => throw new RuntimeException("unexpected")
      }

      Result.foreach(lc.laser.enabledRuntimes) {
        lr => gtf.getExecutorProvider(lr) must haveLaserRuntimeImage(getImage(lr))
      }
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

      ko("fix me")
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

  def haveLaserRuntimeImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("config") /("env") /("public") /("IMAGE" -> name)

  def haveServiceImage(name: => String) = ((_: JsValue).toString) ^^ /("properties") /("services") */("container_spec") /("properties") /("image" -> name)

  def haveImage(name: => String) = ((_:AppSpec).image) ^^ be_==(name)

  def haveEnvVar(pair: => (String,String)) = ((_: AppSpec).env) ^^ havePair(pair)

}
