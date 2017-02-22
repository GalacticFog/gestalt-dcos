package com.galacticfog.gestalt.dcos.marathon

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._
import com.galacticfog.gestalt.dcos.ServiceInfo
import com.galacticfog.gestalt.dcos.ServiceStatus.RUNNING
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.Messages._
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.ServiceData
import com.galacticfog.gestalt.dcos.launcher.GestaltMarathonLauncher.States._
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.google.inject.AbstractModule
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
import scala.util.Success

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

  }

}
