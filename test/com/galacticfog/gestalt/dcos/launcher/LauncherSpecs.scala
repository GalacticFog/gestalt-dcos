package com.galacticfog.gestalt.dcos.launcher

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status.Failure
import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActor, TestFSMRef, TestKit, TestProbe}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.ServiceStatus.RUNNING
import com.galacticfog.gestalt.dcos._
import com.galacticfog.gestalt.dcos.launcher.LauncherFSM.Messages._
import com.galacticfog.gestalt.dcos.launcher.States._
import com.galacticfog.gestalt.dcos.marathon.{EventBusActor, RestClientActor}
import com.galacticfog.gestalt.patch.{PatchOp, PatchOps}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.google.inject.AbstractModule
import mockws.{MockWS, MockWSHelpers, Route}
import net.codingwell.scalaguice.ScalaModule
import org.specs2.execute.Result
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AsyncHttpClientProvider
import play.api.mvc.Results._
import play.api.test._
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient

import scala.collection.mutable
import scala.util.Success

class LauncherSpecs extends PlaySpecification with Mockito with MockWSHelpers {

  class ProbeWrapper(target: ActorRef) extends Actor {
    def receive = {
      case msg => target forward msg
    }
  }

  case class TestModule(sseActor: ActorRef, restActor: ActorRef, ws: WSClient) extends AbstractModule with ScalaModule with AkkaGuiceSupport {
    object WrapperFactory extends EventBusActor.Factory with RestClientActor.Factory {
      def apply(subscriber: ActorRef, eventFilter: Option[Seq[String]]): Actor = new ProbeWrapper(sseActor)
      def apply(): Actor = new ProbeWrapper(restActor)
    }
    override def configure(): Unit = {
      bind[EventBusActor.Factory].toInstance(WrapperFactory)
      bind[RestClientActor.Factory].toInstance(WrapperFactory)
      bind[WSClient].toInstance(ws)
      bind[AsyncHttpClient].toProvider[AsyncHttpClientProvider]
    }
  }

  abstract class WithConfig(config: (String,Any)*)
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val mockSSEClient = TestProbe()
    val mockRestClient = TestProbe()
    val mockWSClient = mock[WSClient]
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(
          sseActor = mockSSEClient.ref,
          restActor = mockRestClient.ref,
          ws = mockWSClient
        ))
        .configure(config:_*)
        .injector
  }

  abstract class WithRoutesAndConfig(routes: MockWS.Routes, config: (String,Any)*)
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val mockSSEClient = TestProbe()
    val mockRestClient = TestProbe()
    val mockWSClient = MockWS(routes)
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(
          sseActor = mockSSEClient.ref,
          restActor = mockRestClient.ref,
          ws = mockWSClient
        ))
        .configure(config:_*)
        .injector
  }

  "GestaltMarathonLauncher" should {

    "use configured GlobalElasticConfig if not provisioning an elastic node" in new WithConfig(

      "logging.provision-elastic" -> false,
      "logging.es-cluster-name"   -> "my-es-cluster",
      "logging.es-host"           -> "test-es.somewhere.com",
      "logging.es-port-rest"      -> 9211,
      "logging.es-port-transport" -> 9311,
      "logging.es-protocol"       -> "https"
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))
      launcher.stateData.globalConfig.elasticConfig must beSome(GlobalElasticConfig(
        hostname = "test-es.somewhere.com",
        protocol = "https",
        portApi = 9211,
        portSvc = 9311,
        clusterName = "my-es-cluster"
      ))
    }

    "for provisioned elastic, set GlobalElasticConfig at init and persist after launching the elastic container" in new WithConfig(
      "logging.provision-elastic" -> true,
      "logging.es-cluster-name"   -> "test-cluster-name"
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        var launched = false
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.LaunchAppRequest(app) if app.id.get.endsWith("/elasticsearch") =>
            sender ! Json.obj()
            launched = true
            keepRunning
          case RestClientActor.GetServiceInfo(ELASTIC) if launched =>
            sender ! ServiceInfo(
              service = ELASTIC,
              vhosts = Seq.empty,
              hostname = Some("192.168.1.51"),
              ports = Seq("9200","9300"),
              status = RUNNING
            )
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))
      launcher.stateData.globalConfig.elasticConfig must beSome(GlobalElasticConfig(
        hostname = "gestalt-framework-elasticsearch.marathon.l4lb.thisdcos.directory",
        protocol = "http",
        portApi = 9200,
        portSvc = 9300,
        clusterName = "test-cluster-name"
      ))

      launcher.setState(
        stateName = LaunchingElastic,
        stateData = ServiceData(
          statuses = Map(),
          adminKey = Some(GestaltAPIKey("key",Some("secret"),UUID.randomUUID(),disabled = false)),
          error = None,
          errorStage = None,
          globalConfig = launcher.stateData.globalConfig,
          connected = true
        )
      )

      expectMsg(Transition(launcher, Uninitialized, LaunchingElastic))

      expectMsg(Transition(launcher, LaunchingElastic, launcher.underlyingActor.nextState(LaunchingElastic)))

      launcher.stateData.globalConfig.elasticConfig must beSome(GlobalElasticConfig(
        hostname = "gestalt-framework-elasticsearch.marathon.l4lb.thisdcos.directory",
        protocol = "http",
        portApi = 9200,
        portSvc = 9300,
        clusterName = "test-cluster-name"
      ))
    }

    "use configured GlobalDBConfig if not provisioning a DB" in new WithConfig(
      "database.provision" -> false,
      "database.username" -> "test-username",
      "database.password" -> "test-password",
      "database.prefix"   -> "gestalt-test-",
      "database.hostname" -> "test-db.somewhere.com",
      "database.port"     -> 5555
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))
      launcher.stateData.globalConfig.dbConfig must beSome( GlobalDBConfig(
        username = "test-username",
        password = "test-password",
        prefix = "gestalt-test-",
        hostname = "test-db.somewhere.com",
        port = 5555
      ))
    }

    "for provisioned db, set GlobalDBConfig at init and persist after launching the database container" in new WithConfig(
      "logging.provision-elastic" -> false,
      "database.provision" -> true,
      "database.username" -> "test-username",
      "database.password" -> "test-password",
      "database.prefix"   -> "gestalt-test-"
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        var launched = false
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.LaunchAppRequest(app) if app.id.get.endsWith("/data-0") =>
            sender ! Json.obj()
            launched = true
            keepRunning
          case RestClientActor.GetServiceInfo(DATA(0)) if launched =>
            sender ! ServiceInfo(
              service = DATA(0),
              vhosts = Seq.empty,
              hostname = Some("192.168.1.50"),
              ports = Seq("5432"),
              status = RUNNING
            )
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))
      launcher.stateData.globalConfig.dbConfig must beSome(GlobalDBConfig(
        username = "test-username",
        password = "test-password",
        hostname = "data-0.gestalt-framework.marathon.mesos",
        port = 5432,
        prefix = "gestalt-test-"
      ))

      launcher.setState(
        stateName = LaunchingDB(0),
        stateData = ServiceData(
          statuses = Map(),
          adminKey = Some(GestaltAPIKey("key",Some("secret"),UUID.randomUUID(),disabled = false)),
          error = None,
          errorStage = None,
          globalConfig = launcher.stateData.globalConfig,
          connected = true
        )
      )

      expectMsg(Transition(launcher, Uninitialized, LaunchingDB(0)))

      expectMsg(Transition(launcher, LaunchingDB(0), launcher.underlyingActor.nextState(LaunchingDB(0))))

      launcher.stateData.globalConfig.dbConfig must beSome(GlobalDBConfig(
        username = "test-username",
        password = "test-password",
        hostname = "data-0.gestalt-framework.marathon.mesos",
        port = 5432,
        prefix = "gestalt-test-"
      ))
    }

    "for provisioned db, launch db after initial service sync" in new WithConfig(
      "logging.provision-elastic" -> false,
      "database.provision" -> true,
      "database.username" -> "test-username",
      "database.password" -> "test-password",
      "database.prefix"   -> "gestalt-test-"
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        var launched = false
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.LaunchAppRequest(app) if app.id.get.endsWith("/data-0") =>
            sender ! Json.obj()
            launched = true
            keepRunning
          case RestClientActor.GetServiceInfo(DATA(0)) if launched =>
            sender ! ServiceInfo(
              service = DATA(0),
              vhosts = Seq.empty,
              hostname = Some("192.168.1.50"),
              ports = Seq("5432"),
              status = RUNNING
            )
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)
      expectMsg(CurrentState(launcher, Uninitialized))

      launcher ! UpdateAllServiceInfo(Seq.empty)
      expectMsg(Transition(launcher, Uninitialized, LaunchingDB(0)))
      expectMsg(Transition(launcher, LaunchingDB(0), launcher.underlyingActor.nextState(LaunchingDB(0))))
    }

    "for non-provisioned db, launch rabbit after initial service sync" in new WithConfig(
      "logging.provision-elastic" -> false,
      "database.provision" -> false,
      "database.username" -> "test-username",
      "database.password" -> "test-password",
      "database.prefix"   -> "gestalt-test-"
    ) {

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        var launched = false
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.LaunchAppRequest(app) if app.id.get.endsWith("/rabbit") =>
            sender ! Json.obj()
            launched = true
            keepRunning
          case RestClientActor.GetServiceInfo(RABBIT) if launched =>
            sender ! ServiceInfo(
              service = RABBIT,
              vhosts = Seq.empty,
              hostname = Some("192.168.1.50"),
              ports = Seq("5672","15672"),
              status = RUNNING
            )
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)
      expectMsg(CurrentState(launcher, Uninitialized))

      launcher ! UpdateAllServiceInfo(Seq.empty)
      expectMsg(Transition(launcher, Uninitialized, LaunchingRabbit))
      expectMsg(Transition(launcher, LaunchingRabbit, launcher.underlyingActor.nextState(LaunchingRabbit)))
    }

    "not shutdown database containers if they were not provisioned even if asked to" in new WithConfig("database.provision" -> false) {
      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])
      launcher.stateName must_== States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.KillAppRequest(svc) if !svc.isInstanceOf[DATA] =>
            sender ! false
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.setState(
        stateName = ShuttingDown, // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = true)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Seq(
        UI, META, SECURITY, RABBIT
      ).foreach {
        svc => mockRestClient.expectMsg(RestClientActor.KillAppRequest(svc))
      }
      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

    "not shutdown database containers unless explicitly instructed to" in new WithConfig("database.provision" -> true) {
      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])
      launcher.stateName must_== States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.KillAppRequest(svc) if !svc.isInstanceOf[DATA] =>
            sender ! false
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.setState(
        stateName = ShuttingDown, // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = false)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Seq(
        UI, META, SECURITY, RABBIT
      ).foreach {
        svc => mockRestClient.expectMsg(RestClientActor.KillAppRequest(svc))
      }
      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

    "shutdown database containers if explicitly instructed to" in new WithConfig("database.provision" -> true, "database.num-secondaries" -> 3) {
      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])
      launcher.stateName must_== States.Uninitialized

      // return Future{false} short-circuits any additional processing
      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case RestClientActor.KillAppRequest(_) =>
            sender ! false
            keepRunning
          case _ =>
            sender ! Failure(new RuntimeException("do not care"))
            noAutoPilot
        }
      })

      launcher.setState(
        stateName = ShuttingDown, // any state except Uninitialized will work
        stateData = ServiceData.init
      )
      val future = launcher ? ShutdownRequest(shutdownDB = true)
      val Success(ShutdownAcceptedResponse) = future.value.get
      Seq(
        UI, META, SECURITY, RABBIT, DATA(3), DATA(2), DATA(1), DATA(0)
      ).foreach {
        svc => mockRestClient.expectMsg(RestClientActor.KillAppRequest(svc))
      }
      launcher.stateName must_== ShuttingDown
      launcher.stateData.error must beNone
      launcher.stateData.errorStage must beNone
    }

  }

  "GestaltMarathonLauncher provision of meta" should {

    val metaHost = "meta.test"
    val metaPort = "14374"
    val demoWrkId  = UUID.randomUUID()
    val demoEnvId  = UUID.randomUUID()
    val sysWrkId   = UUID.randomUUID()
    val dcosProvId = UUID.randomUUID()
    val dbProvId   = UUID.randomUUID()
    val rabbitProvId = UUID.randomUUID()
    val secProvId    = UUID.randomUUID()
    val laserProvId  = UUID.randomUUID()
    val kongProvId   = UUID.randomUUID()
    val policyProvId = UUID.randomUUID()
    val gtwProvId    = UUID.randomUUID()
    val demoLambdaSetupId = UUID.randomUUID()
    val demoLambdaTdownId = UUID.randomUUID()
    val demoApi = UUID.randomUUID()

    val providerCreateAttempts = new AtomicInteger(0)
    val createdBaseDCOS = new AtomicInteger(0)
    val createdDbProvider = new AtomicInteger(0)
    val createdRabbitProvider = new AtomicInteger(0)
    val createdSecProvider = new AtomicInteger(0)
    val createdLaserProvider = new AtomicInteger(0)
    val createdGatewayProvider = new AtomicInteger(0)
    val createdKongProvider = new AtomicInteger(0)
    val createdPolicyProvider = new AtomicInteger(0)
    val createdSetupLambda = new AtomicInteger(0)
    val createdTdownLambda = new AtomicInteger(0)
    val createdSetupLambdaEndpoint = new AtomicInteger(0)
    val createdTdownLambdaEndpoint = new AtomicInteger(0)
    val createdExecProviders: mutable.Map[String, UUID] = scala.collection.mutable.LinkedHashMap[String,UUID]()
    val renamedRootOrg = new AtomicInteger(0)

    val newCompanyDescription = "MyCompany.com!"

    val metaProvisionProviders = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action{Ok(Json.arr())}
      case (POST, u) if u == s"http://$metaHost:$metaPort/migrate" => Action{Ok(Json.obj())}
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/providers" => Action{ request =>
        providerCreateAttempts.getAndIncrement()
        val providerName = (request.body.asJson.get \ "name").as[String]
        val providerType = (request.body.asJson.get \ "resource_type").asOpt[String]
        providerType match {
          case Some(pt) if pt.startsWith("Gestalt::Configuration::Provider::Lambda::Executor::") =>
            val pid = UUID.randomUUID()
            createdExecProviders.synchronized { createdExecProviders += (providerName -> pid) }
            Created(Json.obj(
              "id" -> pid
            ))
          case _ => providerName match {
            case "default-dcos"  =>
              createdBaseDCOS.getAndIncrement()
              Created(Json.obj(
                "id" -> dcosProvId
              ))
            case "default-postgres" =>
              createdDbProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> dbProvId
              ))
            case "default-rabbit" =>
              createdRabbitProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> rabbitProvId
              ))
            case "default-security" =>
              createdSecProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> secProvId
              ))
            case "laser" =>
              createdLaserProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> laserProvId
              ))
            case "kong" =>
              createdKongProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> kongProvId
              ))
            case "policy" =>
              createdPolicyProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> policyProvId
              ))
            case "gwm" =>
              createdGatewayProvider.getAndIncrement()
              Created(Json.obj(
                "id" -> gtwProvId
              ))
            case _ => BadRequest("")
          }
        }
      }
    })

    val emptyOk = Action{Ok(Json.arr())}
    val metaExistenceChecks = Route({
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/workspaces"                            => emptyOk
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/workspaces/$sysWrkId/environments"     => emptyOk
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/workspaces/$demoWrkId/environments"    => emptyOk
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/lambdas"       => emptyOk
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/apis"          => emptyOk
      case (GET, u) if u == s"http://$metaHost:$metaPort/root/apis/$demoApi/apiendpoints"            => emptyOk
    })

    val metaRenameRoot = Route({
      case (PATCH, u) if u == s"http://$metaHost:$metaPort/root" => Action { request =>
        (request.body.asJson.get).asOpt[Seq[PatchOp]] match {
          case Some(Seq(PatchOp(PatchOps.Replace, "/description", Some(_)))) =>
            renamedRootOrg.getAndIncrement()
            Ok(Json.obj())
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionWorkspace = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/workspaces" => Action { request =>
        (request.body.asJson.get \ "name").asOpt[String] match {
          case Some("gestalt-system-workspace") => Created(Json.obj(
            "id" -> sysWrkId
          ))
          case Some("demo") => Created(Json.obj(
            "id" -> demoWrkId
          ))
          case _ => BadRequest(Json.obj())
        }
      }
    })

    val metaProvisionDemoEnv = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/workspaces/$demoWrkId/environments" => Action { request =>
        if ( (request.body.asJson.get \ "name").asOpt[String].contains("demo") )
          Created(Json.obj("id" -> demoEnvId))
        else
          BadRequest("")
      }
    })

    val metaProvisionSysEnvs = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/workspaces/$sysWrkId/environments" => Action { request =>
        if ( (request.body.asJson.get \ "name").asOpt[String].contains("gestalt-system-environment") )
          Created(Json.obj("id" -> UUID.randomUUID()))
        else if ( (request.body.asJson.get \ "name").asOpt[String].contains("gestalt-laser-environment") )
          Created(Json.obj("id" -> UUID.randomUUID()))
        else
          BadRequest("")
      }
    })

    val metaProvisionDemoLambdas = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/lambdas" => Action { request =>
        (request.body.asJson.get \ "name").asOpt[String] match {
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

    val metaProvisionDemoAPI = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/environments/$demoEnvId/apis" => Action { request =>
        (request.body.asJson.get \ "name").asOpt[String] match {
          case Some("demo")    =>
            Created(Json.obj(
              "id" -> demoApi
            ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionDemoEndpoints = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/apis/$demoApi/apiendpoints" => Action { request =>
        (request.body.asJson.get \ "name").asOpt[String] match {
          case Some("-setup")    =>
            createdSetupLambdaEndpoint.getAndIncrement()
            Created(Json.obj(
              "id" -> UUID.randomUUID()
            ))
          case Some("-teardown") =>
            createdTdownLambdaEndpoint.getAndIncrement()
            Created(Json.obj(
              "id" -> UUID.randomUUID()
            ))
          case _ => BadRequest("")
        }
      }
    })

    val metaProvisionLicense = Route({
      case (POST, u) if u == s"http://$metaHost:$metaPort/root/licenses" => Action { request =>
        (request.body.asJson.get \ "properties" \ "data").asOpt[String] match {
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
        orElse metaProvisionWorkspace orElse metaProvisionDemoEnv orElse metaProvisionSysEnvs
        orElse metaProvisionDemoLambdas orElse metaProvisionDemoAPI orElse metaProvisionDemoEndpoints
        orElse metaRenameRoot orElse metaExistenceChecks
        orElse notFoundRoute,
      "meta.company-name" -> newCompanyDescription
    ) {
      mockRestClient.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
          case _ =>
            sender ! Failure(new RuntimeException("do not care whether i can launch apps"))
            keepRunning
        }
      })

      val launcher = TestFSMRef(injector.instanceOf[LauncherFSM])

      launcher.stateName must_== States.Uninitialized

      launcher ! SubscribeTransitionCallBack(testActor)

      expectMsg(CurrentState(launcher, Uninitialized))

      launcher.setState(
        stateName = ProvisioningMeta,
        stateData = ServiceData(
          statuses = Map(
            SECURITY -> ServiceInfo(SECURITY, Seq.empty, Some("security.test"), Seq("9455"), RUNNING),
            META     -> ServiceInfo(META, Seq.empty, Some(metaHost), Seq(metaPort), RUNNING)
          ),
          adminKey = Some(GestaltAPIKey("key",Some("secret"),UUID.randomUUID(),disabled = false)),
          error = None,
          errorStage = None,
          globalConfig = GlobalConfig()
            .withDb(GlobalDBConfig(
              hostname = "test-db.marathon.mesos",
              port = 5432,
              username = "gestaltdev",
              password = "password",
              prefix = "gestalt-"
            ))
            .withSec(GlobalSecConfig(
              hostname = "testsecurity-gestalt.marathon.l4lb.thisdcos.directory",
              port = 9455,
              apiKey = "key",
              apiSecret = "secret",
              realm = Some("https://security.mycompany.com")
            ))
            .withElastic(Some(GlobalElasticConfig(
              hostname = "elastic",
              protocol = "http",
              portApi = 1234,
              portSvc = 1235,
              clusterName = "es-cluster-name"
            ))),
          connected = true
        )
      )

      expectMsg(Transition(launcher, Uninitialized, ProvisioningMeta))

      expectMsg(Transition(launcher, ProvisioningMeta, launcher.underlyingActor.nextState(ProvisioningMeta)))

      //
      providerCreateAttempts.get()          must_== 16
      metaProvisionProviders.timeCalled     must beGreaterThanOrEqualTo(providerCreateAttempts.get() * 2)
      createdBaseDCOS.get()                 must_== 1
      createdDbProvider.get()               must_== 1
      createdRabbitProvider.get()           must_== 1
      createdSecProvider.get()              must_== 1
      createdExecProviders.size             must_== 8
      createdKongProvider.get()             must_== 1
      createdPolicyProvider.get()           must_== 1
      createdLaserProvider.get()            must_== 1
      createdGatewayProvider.get()          must_== 1
      Result.foreach(LauncherConfig.LaserConfig.KNOWN_LASER_RUNTIMES.values.toSeq) {
        lr => createdExecProviders must haveKey(lr.name)
      }
      //
      metaProvisionLicense.timeCalled       must_== 1
      metaProvisionWorkspace.timeCalled     must_== 1
      metaProvisionDemoEnv.timeCalled       must_== 0
      metaProvisionSysEnvs.timeCalled       must_== 2
      //
      metaRenameRoot.timeCalled             must_== 1
      renamedRootOrg.get()                  must_== 1
      //
      metaProvisionDemoLambdas.timeCalled   must_== 0
      createdSetupLambda.get()              must_== 0
      createdTdownLambda.get()              must_== 0
      //
      metaProvisionDemoAPI.timeCalled       must_== 0
      metaProvisionDemoEndpoints.timeCalled must_== 0
      createdSetupLambdaEndpoint.get()      must_== 0
      createdTdownLambdaEndpoint.get()      must_== 0
    }

  }

}
