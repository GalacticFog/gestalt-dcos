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

  "LauncherConfig" should {

    "generate named VIP from requested nested application group with slashes" in new WithAppGroup("/gestalt-tasks-in-test/dev/") {
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-dev-data:5432"
    }

    "generate named VIP from requested nested application group without slashes" in new WithAppGroup( "gestalt-tasks-in-test/dev" ) {
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-dev-data:5432"
    }

    "generate named VIP from requested flat application group without slashes" in new WithAppGroup( "gestalt-tasks-in-test" ) {
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-data:5432"
    }

    "generate named VIP from requested flat application group with slashes" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipLabel(DATA) must_== "/gestalt-tasks-in-test-data:5432"
    }

    "generated named VIP from default app" in new WithAppGroup {
      launcherConfig.vipLabel(DATA) must_== s"/${LauncherConfig.DEFAULT_APP_GROUP}-data:5432"
    }

    "generate VIP hostname from requested nested application group with slashes" in new WithAppGroup( "/gestalt-tasks-in-test/dev/" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-dev-data.marathon.l4lb.thisdcos.directory"
    }

    "generate VIP hostname from requested nested application group without slashes" in new WithAppGroup( "gestalt-tasks-in-test/dev" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-dev-data.marathon.l4lb.thisdcos.directory"
    }

    "generate VIP hostname from requested flat application group without slashes" in new WithAppGroup( "gestalt-tasks-in-test" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-data.marathon.l4lb.thisdcos.directory"
    }

    "generate VIP hostname from requested flat application group with slashes" in new WithAppGroup( "/gestalt-tasks-in-test/" ) {
      launcherConfig.vipHostname(DATA) must_== "gestalt-tasks-in-test-data.marathon.l4lb.thisdcos.directory"
    }

    "generated VIP hostname from default app" in new WithAppGroup {
      launcherConfig.vipHostname(DATA) must_== s"${LauncherConfig.DEFAULT_APP_GROUP}-data.marathon.l4lb.thisdcos.directory"
    }

  }

}
