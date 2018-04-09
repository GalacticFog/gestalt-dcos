package com.galacticfog.gestalt.dcos.marathon

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestKit, TestProbe}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenRequest, DCOSAuthTokenResponse}
import com.google.inject.AbstractModule
import mockws.{MockWS, MockWSHelpers, Route}
import modules.WSClientFactory
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.mvc.{Action, AnyContent, Request, RequestHeader}
import play.api.mvc.Results._
import play.api.mvc.BodyParsers.parse
import play.api.test._

import scala.concurrent.duration._
import scala.util.Try

class MarathonSSEClientSpecs extends PlaySpecification with Mockito with MockWSHelpers {

  val authToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6InNlY3JldCIsInR5cCI6IkpXVCJ9.eyJ1aWQiOiJkZW1vX2dlc3RhbHRfbWV0YSJ9.RP5MhJPey2mDXOJlu1GFcQ_TlWZzYnr_6N7AwDbB0jqJC3bsLR8QxKZVbzk_JInwO5QN_-BVK5bvxP5zyo4KhVotsugH5eP_iTSDyyx7iKWOK4oPmFlJglaXGRE_KEuySAeZCNTnDIrfUWnB21WwS92MGr6B4rFZ-IzVSmygzO-LgxM-ZU9_b9kyKLOUXcQLgHFLY-qJMWou98dTv36lhjqx65iKQ5PT53KjGtL6OQ-1vqXse5ynCJGsXk3HBXV4P_w42RJBIAWiIbsUfgN85sGTVPvtHO-o-GJMknf7G0FiwfGtsYS3n05kirNIwsZS54RX03TNlxq0Vg48eWGZKQ"
  val testServiceId = "meta-dcos-provider"
  val testPrivateKey = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC9OzC0iseKnsqd\nu82KvTav6q+j4MoSS3mGGPZIA2JaD/cMjpzBtaaOxIbcyLWt2M8hwdO3TLXCZiW2\nybz2Koeo3+vNphnO7U4ZggSIuM+RYfhUUnQ79yiYKmL3z93HRrvZBlulG3yOFo5y\n30IFKqyt2QKlPy3ObCtZYwT4opYNnkev/pubtOjsjdkU9/u088eiLfVHwSwpBxjG\n2wbpFVGyN3p55UHW3K6QUrUw8B7EOF2A5EXzgR5GmAgL6SjuzEdghumqdMcSxGoE\n4pL3Y6LHer391ITdxO819o0i3cfglvgXxFGZSsiRVV89X15n8pEbP73cD3sRxnwe\nIwW860ZnAgMBAAECggEAIKUXb+4JIobmWXPOr8KYrpyEFHdxJNrUaifgROggjXz3\nl7j6nghiZXrN8UTG4ujmQuKXTaX0LUdF9lSzPpxzrtSCb4XaKfKSaKAffB614FTQ\nbGuVFcs7u5SEYk//6KLxQS1xnfgx8qk9hd+yGgYUqCEp7awKkPPkPpVwhBw4WrzJ\nkYxJ3bIT7j3svTr5uhno7cFso5jhfFyMA7PruHGNfyOWLIgzgw5qwRUK1WLMyk88\nJivrDRbvuskWK7pxvLrRQ/VA34LvGKLroj9Gqw9HIDGbY526PPjFo/uDq8ErHBsQ\nBdoagN6VihX5YjXdi3eF8mIcaFYBOQj6zB+Kfmkc0QKBgQDjkIemfgpHEMcRsinm\ni0WLlZGD8hjFTNku1Pki5sFffXcHR+FImrEUXL/NqJr8iqIeJ+1cx3OAiBm8PHh4\nl+LYz4H2TlvIEEURmOwLiBbh49N4o7T9the+PluDGLsZ9ka3AGHP1LBcvwYJdf7v\nubK3eky1QQSI5Ce6+uayU76QFQKBgQDU4G4j2eAIVTDQ0xMfJYXFaIh2eVqTkv83\nPeskWhAQcPUKzyX7bPHSdSbx+91ZW5iL8GX4DFp+JBiQFSqNq1tqhLwz9xHTxYrj\nGvi6MUJ4LCOihbU+6JIYuOdxq3govxtnJ+lE4cmwr5Y4HM1wx2dxba9EsItLrzkj\nHGPNDJ6fiwKBgCXgPHO9rsA9TqTnXon8zEp7TokDlpPgQpXE5OKmPbFDFLilgh2v\ngaG9/j6gvYsjF/Ck/KDgoZzXClGGTxbjUOJ9R0hTqnsWGijfpwoUUJqwbNY7iThh\nQnprrpeXWizsDMEQ0zbgU6pcMQkKFrCX2+Ml+/Z/J94Q+3vnntY3khQxAoGAdUkh\n5cbI1E57ktJ4mpSF23n4la3O5bf7vWf0AhdM+oIBwG7ZMmmX4qiBSJnIHs+EgLV2\nuO+1fAJPNjMzOtLKjymKt+bMf607FF1r5Mn3IVbQW17nuT1SISTe/5XFok2Iv5ER\nyM3N3fcgANJ9rkFvEOOpyWKrnItyI5IkunjVfHkCgYEAjmAjQOQt5eCO9kGitL7X\ntQGn8TWWHRCjMm1w3ith7bPp11WrdeyfNuUAB7weQjk2qjAIKTOGWtIRqc36OLPA\nkwF1GDyFXvLqJej/2ZLfytyjhetLAQnRL0qOgCi7EU5+YLXuYnn7zPEJgrR3ogX4\n4rvG4NIQ8wG0sEUTnr06nck=\n-----END PRIVATE KEY-----"
  val testPublicKey  = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvTswtIrHip7KnbvNir02\nr+qvo+DKEkt5hhj2SANiWg/3DI6cwbWmjsSG3Mi1rdjPIcHTt0y1wmYltsm89iqH\nqN/rzaYZzu1OGYIEiLjPkWH4VFJ0O/comCpi98/dx0a72QZbpRt8jhaOct9CBSqs\nrdkCpT8tzmwrWWME+KKWDZ5Hr/6bm7To7I3ZFPf7tPPHoi31R8EsKQcYxtsG6RVR\nsjd6eeVB1tyukFK1MPAexDhdgORF84EeRpgIC+ko7sxHYIbpqnTHEsRqBOKS92Oi\nx3q9/dSE3cTvNfaNIt3H4Jb4F8RRmUrIkVVfPV9eZ/KRGz+93A97EcZ8HiMFvOtG\nZwIDAQAB\n-----END PUBLIC KEY-----"
  val testDcosUrl = "https://m1.dcos/acs/api/v1/auth/login"
  val marathonBaseUrl = "https://my-dcos-cluster/service/marathon"

  val testAppId = "/test/test/test-app"

  val dummyAppPayload = MarathonAppPayload(
    id = Some(testAppId),
    instances = Some(1),
    cpus = Some(1),
    mem = Some(1),
    container = Some(MarathonContainerInfo())
  )

  object TestConfigs {
    val noAuthConfig = Seq(
      "auth.method" -> "none"
    )

    val authConfig = Seq(
      "auth.method" -> "acs",
      "auth.acs_service_acct_creds" -> Json.obj(
        "login_endpoint" -> testDcosUrl,
        "uid" -> testServiceId,
        "private_key" -> testPrivateKey,
        "scheme" -> "RS256"
      ).toString
    )

    val marathonConfig = Seq(
      "marathon.url" -> marathonBaseUrl
    )
  }

  object DCOSMocks {
    def mockAuth() = Route {
      case (POST,url) if url == testDcosUrl => Action {request =>
        val uid = (request.body.asJson.get \ "uid").as[String]
        val jws = io.jsonwebtoken.Jwts.parser().setSigningKey(DCOSAuthTokenActor.strToPublicKey(testPublicKey)).parseClaimsJws((request.body.asJson.get \ "token").as[String])
        if ( uid == testServiceId && jws.getBody.get("uid",classOf[String]) == testServiceId )
          Ok(Json.obj("token" -> authToken))
        else
          Unauthorized(Json.obj("message" -> "epic failure"))
      }
    }
  }

  object MarathonMocks {

    object AuthedRequest {
      def unapply(request: RequestHeader): Option[String] = request.headers.get(AUTHORIZATION).map(_.stripPrefix("token="))
    }

    def launchAuthed: MockWS.Routes = {
      case (POST, url) if url == marathonBaseUrl+s"/v2/apps" => Action{ request =>
        request match {
          case AuthedRequest(authToken) => Ok(Json.obj("id" -> testAppId))
          case _                        => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
        }
      }
    }

    def launchUnauthed: MockWS.Routes = {
      case (POST, url) if url == marathonBaseUrl+s"/v2/apps" => Action{ request =>
        request match {
          case AuthedRequest(_) => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                => Ok(Json.obj("id" -> testAppId))
        }
      }
    }

    def killAuthed: MockWS.Routes = {
      case (DELETE, url) if url == marathonBaseUrl+s"/v2/apps/gestalt-framework/data-0" => Action{ request =>
        request match {
          case AuthedRequest(authToken) => Ok(Json.obj())
          case _                        => Unauthorized(Json.obj("message" -> "i did not get the expected token"))
        }
      }
    }

    def killUnauthed: MockWS.Routes = {
      case (DELETE, url) if url == marathonBaseUrl+s"/v2/apps/gestalt-framework/data-0" => Action{ request =>
        request match {
          case AuthedRequest(_) => Unauthorized(Json.obj("message" -> "i was not expecting a token"))
          case _                => Ok(Json.obj())
        }
      }
    }
  }

  case class TestModule(ws: WSClient) extends AbstractModule with AkkaGuiceSupport with ScalaModule {
    override def configure(): Unit = {
      bind[WSClientFactory].toInstance(new WSClientFactory {
        def getClient = ws
      })
    }
  }

  abstract class WithConfig( config: Seq[(String,Any)] = Seq.empty,
                             routes: MockWS.Routes = PartialFunction.empty )
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val testRoute = Route(routes)

    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .bindings(TestModule(MockWS(testRoute)))
        .configure(config:_*)
        .injector
  }

  abstract class WithMarSSEConfig( config: Seq[(String,Any)] = Seq.empty,
                                   routes: MockWS.Routes = PartialFunction.empty )
    extends WithConfig( config, routes ) {

    val tokenActorProbe = TestProbe()
    tokenActorProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        sender ! DCOSAuthTokenResponse(authToken)
        TestActor.NoAutoPilot
      }
    })
    val marClient = system.actorOf(Props(new MarathonSSEClient(
      injector.instanceOf[LauncherConfig],
      injector.instanceOf[WSClientFactory],
      tokenActorProbe.ref
    )))
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
      val client = injector.instanceOf[MarathonSSEClient]
      val info = client.toServiceInfo(
        service = SECURITY,
        app = baseFakeSec
      )
      info.vhosts must containAllOf(Seq(
        "https://lb.cluster.myco.com:9455"
      ))
    }

    "gather non-default haproxy-group exposure into service endpoints" in new WithConfig() {
      val client = injector.instanceOf[MarathonSSEClient]
      val info = client.toServiceInfo(
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
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_PATH" -> "/blerg"
      )) must containTheSameElementsAs(Seq("https://foo.bar/blerg"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_3_VHOST" -> "foo.org",
        "HAPROXY_1_VHOST" -> "bar.com"
      )) must containTheSameElementsAs(Seq("https://bar.com", "https://foo.org"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_7_VHOST" -> "bloop.io",
        "HAPROXY_1_PATH" -> "nope"
      )) must containTheSameElementsAs(Seq("https://bloop.io"))

    }

    "not gather service ports with HAPROXY_n_ENABLED false" in new WithConfig(Seq("marathon.lb-url" -> "https://lb.cluster.myco.com")) {
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "t"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "true"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "TRUE"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "T"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "y"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "yes"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "Y"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "YES"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> ""
      )) must beEmpty
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_ENABLED" -> "anything"
      )) must beEmpty
    }

    "parse 1.9 marathon response payload and convert to ServiceInfo" in new WithConfig() {
      val client = injector.instanceOf[MarathonSSEClient]
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
        client.toServiceInfo(LauncherConfig.Services.DATA(0), app)
      } must beSuccessfulTry
    }

    "parse pre- and post-1.9 marathon health event payloads" in new WithConfig() {
      val json_new =
        """{
          |  "alive": true,
          |  "appId": "/gestalt-framework-tasks/data-0",
          |  "eventType": "health_status_changed_event",
          |  "instanceId": "gestalt-framework-tasks_data-0.marathon-ac375feb-1ec5-11e7-882b-1681ed3e70e2",
          |  "timestamp": "2017-04-11T15:06:23.984Z",
          |  "version": "2017-04-11T15:06:18.942Z"
          |}
        """.stripMargin

      val json_old =
        """{
          |  "alive": true,
          |  "appId": "/gestalt-framework-tasks/data-0",
          |  "eventType": "health_status_changed_event",
          |  "taskId": "gestalt-framework-tasks_data-0.marathon-ac375feb-1ec5-11e7-882b-1681ed3e70e2",
          |  "timestamp": "2017-04-11T15:06:23.984Z",
          |  "version": "2017-04-11T15:06:18.942Z"
          |}
        """.stripMargin

      MarathonSSEClient.parseEvent[MarathonHealthStatusChange](ServerSentEvent(
        data = json_old,
        `type` = "health_status_changed_event"
      )) must beSome

      MarathonSSEClient.parseEvent[MarathonHealthStatusChange](ServerSentEvent(
        data = json_new,
        `type` = "health_status_changed_event"
      )) must beSome

    }


//    "request auth token from DCOSAuthTokenActor for authed provider" in new WithMarSSEConfig(
//      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig
//    ) {
//      marClient.connectToBus(testActor)
//      tokenActorProbe.expectMsg(DCOSAuthTokenRequest(
//        dcosUrl = testDcosUrl,
//        serviceAccountId = testServiceId,
//        privateKey = testPrivateKey
//      ))
//      expectMsgType[akka.actor.Status.Failure]
//    }

//    "not request auth token from DCOSAuthTokenActor for un-authed provider" in new WithMarSSEConfig(
//      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig
//    ) {
//      marClient.connectToBus(testActor)
//      tokenActorProbe.expectNoMessage(2 seconds)
//      expectMsgType[akka.actor.Status.Failure]
//    }

    "request and use auth token for authed provider in launchApp" in new WithMarSSEConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.launchAuthed
    ) {
      testRoute.timeCalled must_== 0
      val js = await(marClient ? MarathonSSEClient.LaunchAppRequest(dummyAppPayload))
      js must_== Json.obj(
        "id" -> testAppId
      )
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider in launchApp" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.launchUnauthed
    ) {
      testRoute.timeCalled must_== 0
      val js = await(marClient? MarathonSSEClient.LaunchAppRequest(dummyAppPayload))
      js must_== Json.obj(
        "id" -> testAppId
      )
      testRoute.timeCalled must_== 1
    }

    "request and use auth token for authed provider in killApp" in new WithMarSSEConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.killAuthed
    ) {
      testRoute.timeCalled must_== 0
      await((marClient ? MarathonSSEClient.KillAppRequest(DATA(0))).mapTo[Boolean]) must beTrue
      testRoute.timeCalled must_== 1
    }

    "not request or use auth token for unauthed provider in killApp" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      routes = MarathonMocks.killUnauthed
    ) {
      testRoute.timeCalled must_== 0
      await((marClient ? MarathonSSEClient.KillAppRequest(DATA(0))).mapTo[Boolean]) must beTrue
      testRoute.timeCalled must_== 1
    }

  }

  "DCOSAuthTokenActor" should {

    "get token from DCOS ACS and persist it" in new WithMarSSEConfig {
      val acsLogin = DCOSMocks.mockAuth()
      val wscf = new WSClientFactory {
        override def getClient: WSClient = MockWS(acsLogin)
      }
      val tokenActor = TestActorRef(new DCOSAuthTokenActor(wscf))

      acsLogin.timeCalled must_== 0
      val token = await(tokenActor ? DCOSAuthTokenRequest(
        serviceAccountId = testServiceId,
        privateKey = testPrivateKey,
        dcosUrl = testDcosUrl
      ))
      token must_== DCOSAuthTokenResponse(authToken)
      tokenActor.underlyingActor.acsAuthorizationToken must beSome(authToken)
      acsLogin.timeCalled must_== 1
    }

  }

}
