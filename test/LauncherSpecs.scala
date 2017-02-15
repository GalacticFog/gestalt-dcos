import akka.actor.ActorSystem
import akka.pattern.ask
import com.galacticfog.gestalt.dcos.marathon.{GestaltMarathonLauncher, MarathonSSEClient}
import com.google.inject.AbstractModule
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import akka.testkit.{TestActorRef, TestFSMRef}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher.Messages.{ShutdownAcceptedResponse, ShutdownRequest}
import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher.ServiceData
import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher.States.ShuttingDown
import org.specs2.execute.Result

import scala.concurrent.Future
import scala.util.Success

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
  }

  "GestaltMarathonLauncher" should {

    "not shutdown database containers if they were not provisioned even if asked to" in new WithConfig("database.provision" -> false) {
      val launcher = TestFSMRef(injector.instanceOf[GestaltMarathonLauncher])
      launcher.stateName must_== GestaltMarathonLauncher.States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockSSEClient.killApp(any[FrameworkService]) returns Future.successful(false)

      launcher.setState(
        stateName = ShuttingDown,    // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = true)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Result.foreach(Seq(
        RABBIT, SECURITY, KONG, API_GATEWAY, LASER, META, API_PROXY, UI, POLICY
      )) {
        svc => there was one(mockSSEClient).killApp(svc)
      }
      there was no(mockSSEClient).killApp(
        argThat((svc: FrameworkService) => svc.isInstanceOf[DATA])
      )

      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

    "not shutdown database containers unless explicitly instructed to" in new WithConfig("database.provision" -> true) {
      val launcher = TestFSMRef(injector.instanceOf[GestaltMarathonLauncher])
      launcher.stateName must_== GestaltMarathonLauncher.States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockSSEClient.killApp(any[FrameworkService]) returns Future.successful(false)

      launcher.setState(
        stateName = ShuttingDown,    // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = false)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Result.foreach(Seq(
        RABBIT, SECURITY, KONG, API_GATEWAY, LASER, META, API_PROXY, UI, POLICY
      )) {
        svc => there was one(mockSSEClient).killApp(svc)
      }
      there was no(mockSSEClient).killApp(
        argThat((svc: FrameworkService) => svc.isInstanceOf[DATA])
      )

      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

    "shutdown database containers if explicitly instructed to" in new WithConfig("database.provision" -> true, "database.num-secondaries" -> 3) {
      val launcher = TestFSMRef(injector.instanceOf[GestaltMarathonLauncher])
      launcher.stateName must_== GestaltMarathonLauncher.States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockSSEClient.killApp(any[FrameworkService]) returns Future.successful(false)

      launcher.setState(
        stateName = ShuttingDown,    // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = true)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Result.foreach(Seq(
        RABBIT, SECURITY, KONG, API_GATEWAY, LASER, META, API_PROXY, UI, POLICY, DATA(0), DATA(1), DATA(2), DATA(3)
      )) {
        svc => there was one(mockSSEClient).killApp(svc)
      }
      // make sure only DATA(0 to 3) were killed; no less, no more
      there were 4.times(mockSSEClient).killApp(
        argThat((svc: FrameworkService) => svc.isInstanceOf[DATA])
      )

      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

  }

}
