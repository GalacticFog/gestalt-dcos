package com.galacticfog.gestalt.dcos.marathon

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.google.inject.AbstractModule
import de.heikoseeberger.akkasse.ServerSentEvent
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.test._
import scala.util.Try

class MarathonSSEClientSpecs extends PlaySpecification with Mockito {

  case class TestModule(ws: WSClient) extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[WSClient]).toInstance(ws)
    }
  }

  abstract class WithConfig(config: (String,Any)*)
    extends TestKit(ActorSystem("test-system")) with Scope with ImplicitSender {

    val mockWSClient = mock[WSClient]
    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(mockWSClient))
        .configure(config:_*)
        .injector
  }

  "MarathonSSEClient" should {

    val baseFakeSec = MarathonAppPayload(
      id = "/security",
      env = Map.empty,
      instances = 1,
      cpus = 0.1,
      mem = 128,
      disk = 0,
      container = MarathonContainerInfo(
        `type` = "docker",
        volumes = None,
        docker = Some(MarathonDockerContainer(
          image = "image",
          network = "BRIDGE",
          privileged = false,
          parameters = Seq.empty,
          forcePullImage = false,
          portMappings = Some(Seq(
            DockerPortMapping(containerPort=9000, hostPort=None, servicePort=Some(9455), name=Some("api"), protocol="tcp")
          ))
        ))
      ),
      portDefinitions = Some(Seq(
        PortDefinition(port = 9455, None, "tcp", None)
      )),
      requirePorts = false,
      healthChecks = Seq.empty,
      labels = Map(
        "HAPROXY_GROUP" -> "external"
      ),
      tasksHealthy = Some(0),
      tasksStaged = Some(0),
      tasksRunning = Some(0),
      tasksUnhealthy = Some(0)
    )

    "gather service ports (BRIDGED) into service endpoints" in new WithConfig("marathon.lb-url" -> "https://lb.cluster.myco.com") {
      val client = injector.instanceOf[MarathonSSEClient]
      val info = client.toServiceInfo(
        service = SECURITY,
        app = baseFakeSec
      )
      info.vhosts must containAllOf(Seq(
        "https://lb.cluster.myco.com:9455"
      ))
    }

    def mockMarVhostApp(lbls: (String,String)*): MarathonAppPayload = {
      val mockApp = mock[MarathonAppPayload]
      mockApp.labels returns Map(lbls:_*) ++ Map("HAPROXY_GROUP" -> "external")
      mockApp
    }

    "appropriately gather vhosts and vpaths" in {
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar"
      )) must containTheSameElementsAs(Seq("https://foo.bar"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_0_VHOST" -> "foo.bar",
        "HAPROXY_0_PATH"  -> "/blerg"
      )) must containTheSameElementsAs(Seq("https://foo.bar/blerg"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_3_VHOST" -> "foo.org",
        "HAPROXY_1_VHOST" -> "bar.com"
      )) must containTheSameElementsAs(Seq("https://bar.com","https://foo.org"))
      MarathonSSEClient.getVHosts(mockMarVhostApp(
        "HAPROXY_7_VHOST" -> "bloop.io",
        "HAPROXY_1_PATH" -> "nope"
      )) must containTheSameElementsAs(Seq("https://bloop.io"))

    }

    "not gather service ports with HAPROXY_n_ENABLED false" in new WithConfig("marathon.lb-url" -> "https://lb.cluster.myco.com") {
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

    "parse 1.9 marathon response payload and convert to ServiceInfo" in new WithConfig {
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
          |        "image": "galacticfog/postgres_repl:dcos-1.0.1",
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

      Try{
        val app = (json \ "app").as[MarathonAppPayload]
        client.toServiceInfo(LauncherConfig.Services.DATA(0), app)
      } must beSuccessfulTry


    }

    "parse pre- and post-1.9 marathon health event payloads" in new WithConfig {
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

  }

}
