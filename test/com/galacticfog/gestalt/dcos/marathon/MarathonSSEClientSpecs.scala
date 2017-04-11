package com.galacticfog.gestalt.dcos.marathon

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.{LauncherConfig, ServiceInfo}
import com.galacticfog.gestalt.dcos.ServiceStatus.RUNNING
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.Messages._
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.ServiceData
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.States._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.google.inject.AbstractModule
import de.heikoseeberger.akkasse.ServerSentEvent
import mockws.{MockWS, Route}
import org.specs2.execute.Result
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test._

import scala.concurrent.Future
import scala.util.{Success, Try}

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

    val baseFakeKong = MarathonAppPayload(
      id = "/kong",
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
            DockerPortMapping(containerPort=80, hostPort=None, servicePort=Some(8001), name=Some("gateway"), protocol="tcp"),
            DockerPortMapping(containerPort=81, hostPort=None, servicePort=Some(8002), name=Some("service"), protocol="tcp")
          ))
        ))
      ),
      portDefinitions = Some(Seq(
        PortDefinition(port = 8001, None, "tcp", None),
        PortDefinition(port = 8002, None, "tcp", None)
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
        service = KONG,
        app = baseFakeKong
      )
      info.vhosts must containAllOf(Seq(
        "https://lb.cluster.myco.com:8001",
        "https://lb.cluster.myco.com:8002"
      ))
    }

    "not gather service ports with HAPROXY_n_ENABLED false" in new WithConfig("marathon.lb-url" -> "https://lb.cluster.myco.com") {
      val client = injector.instanceOf[MarathonSSEClient]
      val info = client.toServiceInfo(
        service = KONG,
        app = baseFakeKong.copy(
          labels = Map(
            "HAPROXY_GROUP" -> "external",
            "HAPROXY_1_ENABLED" -> "false"
          )
        )
      )
      info.vhosts must containAllOf(Seq(
        "https://lb.cluster.myco.com:8001"
      ))
      info.vhosts must not contain("https://lb.cluster.myco.com:8002")
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
