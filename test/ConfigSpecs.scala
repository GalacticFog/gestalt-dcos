package test

import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services.{DATA, SECURITY}
import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.test._
import play.api.inject.guice.GuiceApplicationBuilder

class ConfigSpecs extends PlaySpecification with Mockito {

  abstract class WithAppGroup(appGroup: String = null) extends Scope {
    val injector = Option(appGroup).foldLeft(
      new GuiceApplicationBuilder().disable[modules.Module]
    )({
      case (builder,appGroup) => builder.configure("marathon.app-group" -> appGroup)
    }).injector

    val launcherConfig = injector.instanceOf[LauncherConfig]
  }

  abstract class WithConfig(config: (String,Any)*) extends Scope {
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .configure(config:_*)
        .injector
    val launcherConfig = injector.instanceOf[LauncherConfig]
  }

  "LauncherConfig" should {

    "strip prefix/suffix slashes from application group" in new WithAppGroup("/gestalt-tasks-in-test/dev/") {
      launcherConfig.marathon.appGroup must_== "gestalt-tasks-in-test/dev"
    }

    "generate named VIP from requested nested application group" in new WithAppGroup("/gestalt-tasks-in-test/dev/") {
      launcherConfig.vipLabel(SECURITY) must_== "/gestalt-tasks-in-test-dev-security:9455"
    }

    "generate named VIP from requested flat application group" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipLabel(SECURITY) must_== "/gestalt-tasks-in-test-security:9455"
    }

    "generated named VIP from default app" in new WithAppGroup {
      launcherConfig.vipLabel(SECURITY) must_== s"/${LauncherConfig.DEFAULT_APP_GROUP}-security:9455"
    }

    "generate VIP hostname from requested nested application group" in new WithAppGroup( "/gestalt-tasks-in-test/dev/" ) {
      launcherConfig.vipHostname(SECURITY) must_== "gestalt-tasks-in-test-dev-security.marathon.l4lb.thisdcos.directory"
    }

    "generate VIP hostname from requested flat application group" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipHostname(SECURITY) must_== "gestalt-tasks-in-test-security.marathon.l4lb.thisdcos.directory"
    }

    "generated VIP hostname from default app" in new WithAppGroup {
      launcherConfig.vipHostname(SECURITY) must_== s"${LauncherConfig.DEFAULT_APP_GROUP}-security.marathon.l4lb.thisdcos.directory"
    }

    "exclude database provisioning based on config" in new WithConfig("database.provision" -> false) {
      launcherConfig.provisionedServices must not contain((service: FrameworkService) => service.isInstanceOf[DATA])
    }

    "include database provisioning based on config" in new WithConfig("database.provision" -> true) {
      launcherConfig.provisionedServices must containAllOf(
        Seq(DATA(0)) ++ (1 to launcherConfig.database.numSecondaries).map(DATA(_))
      )
    }

    "include database provisioning by default" in new WithConfig() {
      launcherConfig.provisionedServices must containAllOf(
        Seq(DATA(0)) ++ (1 to launcherConfig.database.numSecondaries).map(DATA(_))
      )
    }

    "include the appropriate number of database secondaries based on config" in new WithConfig("database.num-secondaries" -> 3) {
      launcherConfig.provisionedServices must containAllOf( (0 to 3).map(DATA(_)) )
    }

  }

}
