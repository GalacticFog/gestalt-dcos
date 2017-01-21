package test

import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.LauncherConfig.Services.DATA
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
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-dev-data:5432"
    }

    "generate named VIP from requested flat application group" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-data:5432"
    }

    "generated named VIP from default app" in new WithAppGroup {
      launcherConfig.vipLabel(DATA) must_== s"/${LauncherConfig.DEFAULT_APP_GROUP}-data:5432"
    }

    "generate VIP hostname from requested nested application group" in new WithAppGroup( "/gestalt-tasks-in-test/dev/" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-dev-data.marathon.l4lb.thisdcos.directory"
    }

    "generate VIP hostname from requested flat application group" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-data.marathon.l4lb.thisdcos.directory"
    }

    "generated VIP hostname from default app" in new WithAppGroup {
      launcherConfig.vipHostname(DATA) must_== s"${LauncherConfig.DEFAULT_APP_GROUP}-data.marathon.l4lb.thisdcos.directory"
    }

    "exclude database provisioning based on config" in new WithConfig("database.provision" -> false) {
      launcherConfig.provisionedServices must not contain(DATA)
    }

    "include database provisioning based on config" in new WithConfig("database.provision" -> true) {
      launcherConfig.provisionedServices must contain(DATA)
    }

    "include database provisioning by default" in new WithConfig() {
      launcherConfig.provisionedServices must contain(DATA)
    }

  }

}
