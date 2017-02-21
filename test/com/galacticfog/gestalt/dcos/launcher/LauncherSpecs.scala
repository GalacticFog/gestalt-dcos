package com.galacticfog.gestalt.dcos.launcher

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.Messages._
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.ServiceData
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.States._
import com.galacticfog.gestalt.dcos.marathon.MarathonSSEClient
import com.google.inject.AbstractModule
import mockws.{MockWS, Route}
import org.specs2.execute.Result
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.api.mvc.Results._
import play.api.test._

import scala.concurrent.Future
import scala.util.Success

class LauncherSpecs extends PlaySpecification with Mockito {

  case class TestModule(sse: MarathonSSEClient, ws: WSClient) extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[MarathonSSEClient]).toInstance(sse)
      bind(classOf[WSClient]).toInstance(ws)
    }
  }

  abstract class WithConfig(config: (String,Any)*)
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val mockSSEClient = mock[MarathonSSEClient]
    val mockWSClient = mock[WSClient]
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(mockSSEClient, mockWSClient))
        .configure(config:_*)
        .injector
  }

  abstract class WithRoutesAndConfig(routes: MockWS.Routes, config: (String,Any)*)
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val mockSSEClient = mock[MarathonSSEClient]
    val mockWSClient = MockWS(routes)
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(mockSSEClient, mockWSClient))
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

    val metaHost = "meta.test"
    val metaPort = 1234

    val metaProvisionKong = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action(Ok(""))
    })

    val metaProvisionDCOS = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action(Ok(""))
    })

    val metaProvisionLicense = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action(Ok(""))
    })

    "provision meta with all expected components" in new WithRoutesAndConfig(
      metaProvisionKong orElse metaProvisionDCOS orElse metaProvisionLicense
    ) {
      val launcher = TestFSMRef(injector.instanceOf[GestaltMarathonLauncher])

      launcher.stateName must_== GestaltMarathonLauncher.States.Uninitialized

      launcher.setState(
        stateName = SyncingMeta,
        stateData = ServiceData.init
      )

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, SyncingMeta))

      launcher ! MetaSyncFinished

      expectMsg(Transition(launcher, SyncingMeta, ProvisioningMeta))

      expectMsg(Transition(launcher, ProvisioningMeta, launcher.underlyingActor.nextState(ProvisioningMeta)))

      metaProvisionKong.timeCalled must_== 1
      metaProvisionDCOS.timeCalled must_== 1
      metaProvisionLicense.timeCalled must_== 1

//      there was one(mockWSClient).url(argThat((url: String) => url.startsWith()))

//      val future = launcher ? ShutdownRequest(shutdownDB = true)
//      val Success(ShutdownAcceptedResponse) = future.value.get
//      Result.foreach(Seq(
//        RABBIT, SECURITY, KONG, API_GATEWAY, LASER, META, API_PROXY, UI, POLICY, DATA(0), DATA(1), DATA(2), DATA(3)
//      )) {
//        svc => there was one(mockSSEClient).killApp(svc)
//      }
//      // make sure only DATA(0 to 3) were killed; no less, no more
//      there were 4.times(mockSSEClient).killApp(
//        argThat((svc: FrameworkService) => svc.isInstanceOf[DATA])
//      )
//
//      launcher.stateName must_== ShuttingDown
//      launcher.stateData.error must beNone
//      launcher.stateData.errorStage must beNone


    }

  }

}
