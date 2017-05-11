package com.galacticfog.gestalt.dcos.launcher

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

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
import com.galacticfog.gestalt.patch.{PatchOp, PatchOps}
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
        RABBIT, SECURITY, META, UI
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
        RABBIT, SECURITY, META, UI
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
        RABBIT, SECURITY, META, UI, DATA(0), DATA(1), DATA(2), DATA(3)
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
    val dcosProvId = UUID.randomUUID()
    val kongProvId = UUID.randomUUID()
    val demoLambdaSetupId = UUID.randomUUID()
    val demoLambdaTdownId = UUID.randomUUID()

    val createdBaseDCOS = new AtomicInteger(0)
    val createdBaseKong = new AtomicInteger(0)
    val createdSetupLambda = new AtomicInteger(0)
    val createdTdownLambda = new AtomicInteger(0)
    val createdSetupLambdaEndpoint = new AtomicInteger(0)
    val createdTdownLambdaEndpoint = new AtomicInteger(0)
    val renamedRootOrg = new AtomicInteger(0)

    val newCompanyDescription = "MyCompany.com!"

    val metaProvisionProviders = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action{Ok(Json.arr())}
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("default-dcos")  =>
            createdBaseDCOS.getAndIncrement()
            Created(Json.obj(
              "id" -> UUID.randomUUID()
            ))
//          case Some("base-kong")      =>
//            createdBaseKong.getAndIncrement()
//            Created(Json.obj(
//            "id" -> kongProvId
//          ))
          case _ => BadRequest("")
        }
      }
    })

    val metaRenameRoot = Route({
      case (PATCH, u) if u == s"http://$metaHost:$metaPort/root" => Action(BodyParsers.parse.json) { request =>
        (request.body).asOpt[Seq[PatchOp]] match {
          case Some(Seq(PatchOp(PatchOps.Replace, "/description", Some(newCompanyDescription)))) =>
            renamedRootOrg.getAndIncrement()
            Ok(Json.obj())
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
        if ( (request.body \ "name").asOpt[String].contains("demo") &&
             (request.body \ "properties" \ "environment_type").asOpt[String].contains("production") )
          Created(Json.obj("id" -> demoEnvId))
        else
          BadRequest("")
      }
    })

    val metaProvisionDemoLambdas = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/lambdas" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo-setup")    =>
            createdSetupLambda.getAndIncrement()
            Created(Json.obj(
              "id" -> demoLambdaSetupId
            ))
          case Some("demo-teardown") =>
            createdTdownLambda.getAndIncrement()
            Created(Json.obj(
              "id" -> demoLambdaTdownId
            ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoEndpoints = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/apiendpoints" => Action(BodyParsers.parse.json) { request =>
        (request.body \ "name").asOpt[String] match {
          case Some("demo-setup")    =>
            createdSetupLambdaEndpoint.getAndIncrement()
            Created(Json.arr(Json.obj(
              "id" -> UUID.randomUUID()
            )))
          case Some("demo-teardown") =>
            createdTdownLambdaEndpoint.getAndIncrement()
            Created(Json.arr(Json.obj(
              "id" -> UUID.randomUUID()
            )))
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

    "provision meta with all expected components and configured company name" in new WithRoutesAndConfig(
      metaProvisionProviders orElse metaProvisionLicense
        orElse metaProvisionDemoWrk orElse metaProvisionDemoEnv
        orElse metaProvisionDemoLambdas orElse metaProvisionDemoEndpoints
        orElse metaRenameRoot
        orElse notFoundRoute,
      "meta.company-name" -> newCompanyDescription
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
            META     -> ServiceInfo(META, Seq.empty, Some(metaHost), Seq(metaPort), RUNNING)
          ),
          adminKey = Some(GestaltAPIKey("key",Some("secret"),UUID.randomUUID(),false)),
          error = None,
          errorStage = None,
          connected = true
        )
      )

      expectMsg(Transition(launcher, Uninitialized, ProvisioningMeta))

      expectMsg(Transition(launcher, ProvisioningMeta, launcher.underlyingActor.nextState(ProvisioningMeta)))

      metaProvisionProviders.timeCalled     must_== 2 // two existence checks, two creations
      createdBaseDCOS.get() must_== 1
      createdBaseKong.get() must_== 0
      metaProvisionLicense.timeCalled       must_== 1
      metaProvisionDemoWrk.timeCalled       must_== 0
      metaProvisionDemoEnv.timeCalled       must_== 0
      metaProvisionDemoLambdas.timeCalled   must_== 0
      createdSetupLambda.get() must_== 0
      createdTdownLambda.get() must_== 0
      metaProvisionDemoEndpoints.timeCalled must_== 0
      createdSetupLambdaEndpoint.get() must_== 0
      createdTdownLambdaEndpoint.get() must_== 0
      metaRenameRoot.timeCalled must_== 1
      renamedRootOrg.get()      must_== 1
    }

  }

}
