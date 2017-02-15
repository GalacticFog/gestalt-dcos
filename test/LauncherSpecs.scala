import akka.actor.ActorSystem
import com.galacticfog.gestalt.dcos.marathon.{GestaltMarathonLauncher, MarathonSSEClient}
import com.google.inject.AbstractModule
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import akka.testkit.{TestActorRef, TestFSMRef}

class LauncherSpecs extends PlaySpecification with Mockito {

  case class TestModule(sse: MarathonSSEClient) extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[MarathonSSEClient]).toInstance(sse);
    }
  }

  abstract class WithConfig(config: (String,Any)*) extends Scope {
    implicit val system = ActorSystem()
    val mockSSEClient = mock[MarathonSSEClient]
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .bindings(TestModule(mockSSEClient))
        .configure(config:_*)
        .injector
    val launcher = injector.instanceOf[GestaltMarathonLauncher]
  }

  "GestaltMarathonLauncher" should {

    "not shutdown database containers unless explicitly instructed to" in new WithConfig("database.provision" -> false) {
      val launcherRef = TestFSMRef(launcher)
      launcherRef.stateName must_== GestaltMarathonLauncher.States.Uninitialized
    }

  }

}
