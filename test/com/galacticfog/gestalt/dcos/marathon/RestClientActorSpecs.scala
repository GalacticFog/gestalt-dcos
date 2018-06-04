package com.galacticfog.gestalt.dcos.marathon

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenRequest, DCOSAuthTokenResponse}
import com.galacticfog.gestalt.dcos.{LauncherConfig, ServiceInfo}
import mockws.{MockWS, MockWSHelpers}
import modules.WSClientFactory
import org.specs2.mock.Mockito
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.RequestHeader
import play.api.mvc.Results._
import play.api.test._

import scala.util.Try

class RestClientActorSpecs extends PlaySpecification with Mockito with MockWSHelpers with TestHelper {

  object MarathonMocks {

    object AuthedRequest {
      def unapply(request: RequestHeader): Option[String] = request.headers.get(AUTHORIZATION).map(_.stripPrefix("token="))
    }

    def launch(auth: Boolean): MockWS.Routes = {
      case (POST, url) if url == marathonBaseUrl+s"/v2/apps" => Action { request =>
        request match {
          case AuthedRequest(_) if  auth => Ok(Json.obj("id" -> testAppId))
          case AuthedRequest(_) if !auth => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                if  auth => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
          case _                if !auth => Ok(Json.obj("id" -> testAppId))
        }
      }
    }

    def kill(auth: Boolean): MockWS.Routes = {
      case (DELETE, url) if url == marathonBaseUrl+s"/v2/apps/gestalt-framework/data-0" => Action { request =>
        request match {
          case AuthedRequest(_) if  auth => Ok(Json.obj())
          case AuthedRequest(_) if !auth => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                if  auth => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
          case _                if !auth => Ok(Json.obj())
        }
      }
    }

    def getApp(auth: Boolean): MockWS.Routes = {
      case (GET, url) if url == marathonBaseUrl+s"/v2/apps/gestalt-framework/data-0" => Action { request =>
        val resp = Json.parse(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/marathon-app-response.json")).getLines().mkString)
        request match {
          case AuthedRequest(_) if  auth => Ok(resp)
          case AuthedRequest(_) if !auth => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                if  auth => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
          case _                if !auth => Ok(resp)
        }
      }
    }

    def getGroup(auth: Boolean): MockWS.Routes = {
      case (GET, url) if url == marathonBaseUrl+s"/v2/groups/gestalt-framework" => Action { request =>
        val resp = Json.parse(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/marathon-apps-response.json")).getLines().mkString)
        request match {
          case AuthedRequest(_) if  auth => Ok(resp)
          case AuthedRequest(_) if !auth => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                if  auth => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
          case _                if !auth => Ok(resp)
        }
      }
    }
  }

  abstract class WithRestClientConfig( config: Seq[(String,Any)] = Seq.empty,
                                       routes: MockWS.Routes = PartialFunction.empty )
    extends WithConfig( config, routes ) {

    val tokenActorProbe = TestProbe("token-actor-probe")
    tokenActorProbe.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        sender ! DCOSAuthTokenResponse(authToken)
        TestActor.KeepRunning
      }
    })
    val marClient = system.actorOf(Props(new RestClientActor(
      injector.instanceOf[LauncherConfig],
      injector.instanceOf[WSClientFactory],
      tokenActorProbe.ref
    )), "test-marathon-rest-client")
  }

  "MarathonSSEClient" should {

    val baseFakeSec = MarathonAppPayload(
      id = Some("/security"),
      env = Some(Json.obj()),
      instances = Some(1),
      cpus = Some(0.1),
      mem = Some(128),
      disk = Some(0),
      container = Some(MarathonContainerInfo(
        `type` = Some(MarathonContainerInfo.Types.DOCKER),
        docker = Some(MarathonDockerContainer(
          image = Some("image"),
          network = Some("BRIDGE"),
          privileged = Some(false),
          parameters = Some(Seq.empty),
          forcePullImage = Some(false),
          portMappings = Some(Seq(
            DockerPortMapping(containerPort = Some(9000), hostPort = None, servicePort = Some(9455), name = Some("api"), protocol = Some("tcp"))
          ))
        ))
      )),
      portDefinitions = Some(Seq(
        PortDefinition(port = Some(9455), None, Some("tcp"), None)
      )),
      requirePorts = Some(false),
      healthChecks = Some(Seq.empty),
      labels = Some(Map(
        "HAPROXY_GROUP" -> "external"
      )),
      tasksHealthy = Some(0),
      tasksStaged = Some(0),
      tasksRunning = Some(0),
      tasksUnhealthy = Some(0)
    )

    "gather service ports (BRIDGED) into service endpoints" in new WithConfig(Seq("marathon.lb-url" -> "https://lb.cluster.myco.com")) {
      val marClient = TestActorRef[RestClientActor](Props(new RestClientActor(
        injector.instanceOf[LauncherConfig],
        injector.instanceOf[WSClientFactory],
        testActor
      )), "test-marathon-sse-client")
      val info = marClient.underlyingActor.toServiceInfo(
        service = SECURITY,
        app = baseFakeSec
      )
      info.vhosts must containAllOf(Seq(
        "https://lb.cluster.myco.com:9455"
      ))
    }

    "gather non-default haproxy-group exposure into service endpoints" in new WithConfig() {
      val marClient = TestActorRef[RestClientActor](Props(new RestClientActor(
        injector.instanceOf[LauncherConfig],
        injector.instanceOf[WSClientFactory],
        testActor
      )), "test-marathon-sse-client")
      val info = marClient.underlyingActor.toServiceInfo(
        service = SECURITY,
        app = MarathonAppPayload(
        id = Some("/security"),
        env = Some(Json.obj()),
        instances = Some(1),
        cpus = Some(0.1),
        mem = Some(128),
        disk = Some(0),
        container = Some(MarathonContainerInfo(
          `type` = Some(MarathonContainerInfo.Types.DOCKER),
          docker = Some(MarathonDockerContainer(
            image = Some("image"),
            network = Some("BRIDGE"),
            privileged = Some(false),
            parameters = Some(Seq.empty),
            forcePullImage = Some(false),
            portMappings = Some(Seq(
              DockerPortMapping(containerPort = Some(9000), hostPort = None, servicePort = Some(9455), name = Some("api"), protocol = Some("tcp"))
            ))
          ))
        )),
        portDefinitions = Some(Seq(
          PortDefinition(port = Some(9455), None, Some("tcp"), None)
        )),
        requirePorts = Some(false),
        healthChecks = Some(Seq.empty),
        labels = Some(Map(
          "HAPROXY_GROUP" -> "custom-haproxy-group",
          "HAPROXY_0_VHOST" -> "security.test.com"
        )),
        tasksHealthy = Some(0),
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0)
      ))
      info.vhosts must containAllOf(Seq(
        "https://security.test.com"
      ))
    }

    def mockMarVhostApp(lbls: (String, String)*): MarathonAppPayload = {
      val mockApp = mock[MarathonAppPayload]
      mockApp.labels returns Some(Map(lbls: _*) ++ Map("HAPROXY_GROUP" -> "external"))
      mockApp
    }

    "appropriately gather vhosts and vpaths" in {
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_PATH" -> "/blerg"
      )) must containTheSameElementsAs(Seq("https://foo.bar/blerg"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_3_VHOST" -> "foo.org",
        "HAPROXY_1_VHOST" -> "bar.com"
      )) must containTheSameElementsAs(Seq("https://bar.com", "https://foo.org"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_7_VHOST" -> "bloop.io",
        "HAPROXY_1_PATH" -> "nope"
      )) must containTheSameElementsAs(Seq("https://bloop.io"))

    }

    "not gather service ports with HAPROXY_n_ENABLED false" in new WithConfig(Seq("marathon.lb-url" -> "https://lb.cluster.myco.com")) {
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "t"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "true"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "TRUE"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "T"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "y"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "yes"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "Y"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "YES"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> ""
      )) must beEmpty
      RestClientActor.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "anything"
      )) must beEmpty
    }

    "parse 1.9 marathon response payload and convert to ServiceInfo" in new WithConfig() {
      val marClient = TestActorRef[RestClientActor](Props(new RestClientActor(
        injector.instanceOf[LauncherConfig],
        injector.instanceOf[WSClientFactory],
        testActor
      )), "test-marathon-sse-client")
      val json = Json.parse(
        """
          |{
          |  "app": {
          |    "id": "/gestalt-framework-tasks/data-0",
          |    "cmd": null,
          |    "args": null,
          |    "user": null,
          |    "env": {
          |      "POSTGRES_USER": "gestaltdev",
          |      "POSTGRES_PASSWORD": "letmein",
          |      "PGDATA": "/mnt/mesos/sandbox/pgdata",
          |      "PGREPL_TOKEN": "iw4nn4b3likeu",
          |      "PGREPL_ROLE": "PRIMARY"
          |    },
          |    "instances": 1,
          |    "cpus": 1,
          |    "mem": 512,
          |    "disk": 0,
          |    "gpus": 0,
          |    "executor": "",
          |    "constraints": [],
          |    "uris": [],
          |    "fetch": [],
          |    "storeUrls": [],
          |    "backoffSeconds": 1,
          |    "backoffFactor": 1.15,
          |    "maxLaunchDelaySeconds": 3600,
          |    "container": {
          |      "type": "DOCKER",
          |      "volumes": [
          |        {
          |          "containerPath": "pgdata",
          |          "mode": "RW",
          |          "persistent": {
          |            "size": 100,
          |            "type": "root",
          |            "constraints": []
          |          }
          |        }
          |      ],
          |      "docker": {
          |        "image": "galacticfog/postgres_repl:release-1.2.0",
          |        "network": "BRIDGE",
          |        "portMappings": [
          |          {
          |            "containerPort": 5432,
          |            "hostPort": 0,
          |            "servicePort": 10102,
          |            "protocol": "tcp",
          |            "name": "sql",
          |            "labels": {
          |              "VIP_0": "/gestalt-framework-tasks-data-primary:5432"
          |            }
          |          }
          |        ],
          |        "privileged": false,
          |        "parameters": [],
          |        "forcePullImage": true
          |      }
          |    },
          |    "healthChecks": [
          |      {
          |        "gracePeriodSeconds": 300,
          |        "intervalSeconds": 30,
          |        "timeoutSeconds": 15,
          |        "maxConsecutiveFailures": 4,
          |        "portIndex": 0,
          |        "protocol": "TCP"
          |      }
          |    ],
          |    "readinessChecks": [],
          |    "dependencies": [],
          |    "upgradeStrategy": {
          |      "minimumHealthCapacity": 0.5,
          |      "maximumOverCapacity": 0
          |    },
          |    "labels": {},
          |    "ipAddress": null,
          |    "version": "2017-04-11T15:06:18.942Z",
          |    "residency": {
          |      "relaunchEscalationTimeoutSeconds": 3600,
          |      "taskLostBehavior": "WAIT_FOREVER"
          |    },
          |    "secrets": {},
          |    "taskKillGracePeriodSeconds": 300,
          |    "unreachableStrategy": "disabled",
          |    "killSelection": "YOUNGEST_FIRST",
          |    "ports": [
          |      10102
          |    ],
          |    "portDefinitions": [
          |      {
          |        "port": 10102,
          |        "protocol": "tcp",
          |        "name": "default",
          |        "labels": {}
          |      }
          |    ],
          |    "requirePorts": true,
          |    "versionInfo": {
          |      "lastScalingAt": "2017-04-11T15:06:18.942Z",
          |      "lastConfigChangeAt": "2017-04-11T14:46:40.418Z"
          |    },
          |    "tasksStaged": 0,
          |    "tasksRunning": 1,
          |    "tasksHealthy": 1,
          |    "tasksUnhealthy": 0,
          |    "deployments": [],
          |    "tasks": [
          |      {
          |        "localVolumes": [
          |          {
          |            "runSpecId": "/gestalt-framework-tasks/data-0",
          |            "containerPath": "pgdata",
          |            "uuid": "ac3738da-1ec5-11e7-882b-1681ed3e70e2",
          |            "persistenceId": "gestalt-framework-tasks_data-0#pgdata#ac3738da-1ec5-11e7-882b-1681ed3e70e2"
          |          }
          |        ],
          |        "ipAddresses": [
          |          {
          |            "ipAddress": "172.17.0.2",
          |            "protocol": "IPv4"
          |          }
          |        ],
          |        "stagedAt": "2017-04-11T15:06:18.987Z",
          |        "state": "TASK_RUNNING",
          |        "ports": [
          |          29262
          |        ],
          |        "startedAt": "2017-04-11T15:06:20.048Z",
          |        "version": "2017-04-11T15:06:18.942Z",
          |        "id": "gestalt-framework-tasks_data-0.ac375feb-1ec5-11e7-882b-1681ed3e70e2",
          |        "appId": "/gestalt-framework-tasks/data-0",
          |        "slaveId": "006f3d50-9393-4a73-96df-5da3e76ef0f6-S1",
          |        "host": "10.0.1.30",
          |        "healthCheckResults": [
          |          {
          |            "alive": true,
          |            "consecutiveFailures": 0,
          |            "firstSuccess": "2017-04-11T15:06:23.984Z",
          |            "lastFailure": null,
          |            "lastSuccess": "2017-04-11T15:06:23.984Z",
          |            "lastFailureCause": null,
          |            "instanceId": "gestalt-framework-tasks_data-0.marathon-ac375feb-1ec5-11e7-882b-1681ed3e70e2"
          |          }
          |        ]
          |      }
          |    ]
          |  }
          |}
        """.stripMargin
      )

      Try {
        val app = (json \ "app").as[MarathonAppPayload]
        marClient.underlyingActor.toServiceInfo(LauncherConfig.Services.DATA(0), app)
      } must beSuccessfulTry
    }

    "request and use auth token for authed provider for LaunchAppRequest" in new WithRestClientConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.launch(auth = true)
    ) {
      testRoute.timeCalled must_== 0
      val fresp = (marClient ? RestClientActor.LaunchAppRequest(dummyAppPayload)).mapTo[JsValue]
      tokenActorProbe.expectMsg(DCOSAuthTokenRequest(
        dcosUrl = testDcosUrl,
        serviceAccountId = testServiceId,
        privateKey = testPrivateKey
      ))
      await(fresp) must_== Json.obj(
        "id" -> testAppId
      )
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider for LaunchAppRequest" in new WithRestClientConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.launch(auth = false)
    ) {
      testRoute.timeCalled must_== 0
      val js = await(marClient ? RestClientActor.LaunchAppRequest(dummyAppPayload))
      js must_== Json.obj(
        "id" -> testAppId
      )
      testRoute.timeCalled must_== 1
    }

    "request and use auth token for authed provider for KillAppRequest" in new WithRestClientConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.kill(auth = true)
    ) {
      testRoute.timeCalled must_== 0
      val fresp = (marClient ? RestClientActor.KillAppRequest(DATA(0))).mapTo[Boolean]
      await(fresp) must beTrue
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider for KillAppRequest" in new WithRestClientConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.kill(auth = false)
    ) {
      testRoute.timeCalled must_== 0
      await((marClient ? RestClientActor.KillAppRequest(DATA(0))).mapTo[Boolean]) must beTrue
      testRoute.timeCalled must_== 1
    }

    "request and use auth token for authed provider for GetServiceInfo" in new WithRestClientConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.getApp(auth = true)
    ) {
      testRoute.timeCalled must_== 0
      val fresp = (marClient ? RestClientActor.GetServiceInfo(DATA(0))).mapTo[ServiceInfo]
      await(fresp).service must_== DATA(0)
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider GetServiceInfo" in new WithRestClientConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.getApp(auth = false)
    ) {
      testRoute.timeCalled must_== 0
      await((marClient ? RestClientActor.GetServiceInfo(DATA(0))).mapTo[ServiceInfo]).service must_== DATA(0)
      testRoute.timeCalled must_== 1
    }

    "request and use auth token for authed provider for GetAllServiceInfo" in new WithRestClientConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.getGroup(auth = true)
    ) {
      testRoute.timeCalled must_== 0
      val fresp = (marClient ? RestClientActor.GetAllServiceInfo).mapTo[Seq[ServiceInfo]]
      await(fresp).head.service must_== DATA(0)
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider for GetAllServiceInfo" in new WithRestClientConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.getGroup(auth = false)
    ) {
      testRoute.timeCalled must_== 0
      await((marClient ? RestClientActor.GetAllServiceInfo).mapTo[Seq[ServiceInfo]]).head.service must_== DATA(0)
      testRoute.timeCalled must_== 1
    }

  }

}
