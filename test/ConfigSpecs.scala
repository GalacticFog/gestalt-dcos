package test

import com.galacticfog.gestalt.dcos.LauncherConfig
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.test._
import play.api.inject.guice.GuiceApplicationBuilder

class ConfigSpecs extends PlaySpecification with Mockito {

  abstract class WithConfig(config: (String,Any)*) extends Scope {
    val injector = new GuiceApplicationBuilder()
      .configure(config:_*)
      .disable[modules.Module]
      .injector

    val launcherConfig = injector.instanceOf[LauncherConfig]
  }

  "LauncherConfig" should {

    "generate named VIP from requested nested application group with slashes" in new WithConfig(
      "marathon.app-group" -> "/gestalt-tasks-in-test/dev/"
    ) {
      launcherConfig.vip("test") must_== "/test.dev.gestalt-tasks-in-test"
    }

    "generate named VIP from requested nested application group without slashes" in new WithConfig(
      "marathon.app-group" -> "gestalt-tasks-in-test/dev"
    ) {
      launcherConfig.vip("test") must_== "/test.dev.gestalt-tasks-in-test"
    }

    "generate named VIP from requested flat application group without slashes" in new WithConfig(
      "marathon.app-group" -> "gestalt-tasks-in-test"
    ) {
      launcherConfig.vip("test") must_== "/test.gestalt-tasks-in-test"
    }

    "generate named VIP from requested flat application group with slashes" in new WithConfig(
      "marathon.app-group" -> "/gestalt-tasks-in-test/"
    ) {
      launcherConfig.vip("test") must_== "/test.gestalt-tasks-in-test"
    }

    "generated named VIP from default app" in new WithConfig() {
      launcherConfig.vip("test") must_== s"/test.${LauncherConfig.DEFAULT_APP_GROUP}"
    }

  }

}
