package com.galacticfog.gestalt.dcos.launcher

import java.util.UUID

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.ServiceInfo
import com.galacticfog.gestalt.dcos.ServiceStatus.RUNNING
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.Messages._
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.ServiceData
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.States._
import com.galacticfog.gestalt.dcos.marathon.{MarathonAppPayload, MarathonSSEClient}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.google.inject.AbstractModule
import mockws.{MockWS, Route}
import org.specs2.execute.Result
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.api.mvc.Results._
import play.api.test._
import scala.concurrent.duration._

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
        stateName = ShuttingDown, // any state except Uninitialized will work
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
        stateName = ShuttingDown, // any state except Uninitialized will work
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
        stateName = ShuttingDown, // any state except Uninitialized will work
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

  "GestaltMarathonLauncher provision of meta" should {

    val metaHost = "meta.test"
    val metaPort = "14374"
    val demoWkspId = UUID.randomUUID()
    val demoEnvId  = UUID.randomUUID()
    val kongProvId = UUID.randomUUID()
    val demoLambdaSetupId = UUID.randomUUID()
    val demoLambdaTeardownId = UUID.randomUUID()

    val metaProvisionProviders = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("base-marathon")  => Created(Json.obj(
            "id" -> UUID.randomUUID()
          ))
          case Some("base-kong")      => Created(Json.obj(
            "id" -> kongProvId
          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoWrk = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/workspaces" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo") => Created(Json.obj(
            "id" -> demoWkspId
          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoEnv = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/workspaces/$demoWkspId/environments" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo") => Created(Json.obj(
            "id" -> demoEnvId
          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoLambdas = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/lambdas" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo-setup")    => Created(Json.obj(
            "id" -> demoLambdaSetupId
          ))
          case Some("demo-teardown") => Created(Json.obj(
            "id" -> demoLambdaTeardownId
          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoEndpoints = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/apiendpoints" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo-setup")    => Created(Json.obj(
            "id" -> UUID.randomUUID()
          ))
          case Some("demo-teardown") => Created(Json.obj(
            "id" -> UUID.randomUUID()
          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionLicense = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/licenses" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "properties" \ "data").asOpt[String] match {
          case Some(s) if s.nonEmpty => Created("")
          case _ => BadRequest("")
        }
      }
    })

    val notFoundRoute = Route({
      case (_,_) => Action(NotFound(""))
    })

    "provision meta with all expected components" in new WithRoutesAndConfig(
      metaProvisionProviders orElse metaProvisionLicense
        orElse metaProvisionDemoWrk orElse metaProvisionDemoEnv
        orElse metaProvisionDemoLambdas orElse metaProvisionDemoEndpoints
        orElse notFoundRoute
    ) {

      mockSSEClient.launchApp(any[MarathonAppPayload]) returns Future.failed(new RuntimeException("i don't care whether i can launch apps"))

      val launcher = TestFSMRef(injector.instanceOf[GestaltMarathonLauncher])

      launcher.stateName must_== GestaltMarathonLauncher.States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))

      launcher.setState(
        stateName = ProvisioningMeta,
        stateData = ServiceData(
          statuses = Map(
            SECURITY -> ServiceInfo(SECURITY, Seq.empty, Some("security.test"), Seq("9455"), RUNNING),
            META     -> ServiceInfo(META, Seq.empty, Some(metaHost), Seq(metaPort), RUNNING),
            KONG     -> ServiceInfo(KONG, Seq.empty, Some("kong.test"), Seq("8000", "8001"), RUNNING)
          ),
          adminKey = Some(GestaltAPIKey("key",Some("secret"),UUID.randomUUID(),false)),
          error = None,
          errorStage = None,
          connected = true
        )
      )

      expectMsg(Transition(launcher, Uninitialized, ProvisioningMeta))

      expectMsg(Transition(launcher, ProvisioningMeta, launcher.underlyingActor.nextState(ProvisioningMeta)))

      metaProvisionProviders.timeCalled     must_== 2
      metaProvisionLicense.timeCalled       must_== 1
      metaProvisionDemoWrk.timeCalled       must_== 1
      metaProvisionDemoEnv.timeCalled       must_== 1
      metaProvisionDemoLambdas.timeCalled   must_== 2
      metaProvisionDemoEndpoints.timeCalled must_== 2
    }

  }

}
