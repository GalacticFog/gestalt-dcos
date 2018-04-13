package com.galacticfog.gestalt.dcos.marathon

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenRequest, DCOSAuthTokenResponse}
import com.galacticfog.gestalt.dcos.marathon.EventBusActor.MarathonHealthStatusChange
import mockws.{MockWS, MockWSHelpers}
import org.specs2.mock.Mockito
import play.api.test._

import scala.concurrent.duration._

class DummySupervisor extends Actor {
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: RuntimeException => Stop
  }
  override def receive: Receive = { case _ => }
}

class EventBusActorSpecs extends PlaySpecification with Mockito with MockWSHelpers with TestHelper {

  abstract class WithMarSSEConfig( config: Seq[(String,Any)] = Seq.empty,
                                   routes: MockWS.Routes = PartialFunction.empty )
    extends WithConfig( config, routes ) {

    val tokenActorProbe = TestProbe("token-actor-probe")
    tokenActorProbe.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        sender ! DCOSAuthTokenResponse(authToken)
        TestActor.KeepRunning
      }
    })
    val subscriberProbe = TestProbe("test-subscriber-probe")

    val supervisor = TestActorRef[DummySupervisor]
    val busActor = TestActorRef(Props(new EventBusActor(
      injector.instanceOf[LauncherConfig],
      tokenActorProbe.ref,
      subscriberProbe.ref,
      Seq.empty
    )), supervisor, "test-marathon-sse-client")

  }

  "EventBusActor" should {

    "request auth token from DCOSAuthTokenActor for authed provider" in new WithMarSSEConfig(
      config = TestConfigs.authConfig ++ TestConfigs.marathonConfig
    ) {
      tokenActorProbe.expectMsg(DCOSAuthTokenRequest(
        dcosUrl = testDcosUrl,
        serviceAccountId = testServiceId,
        privateKey = testPrivateKey
      ))
      subscriberProbe.expectMsgType[EventBusActor.EventBusFailure]
    }

    "not request auth token from DCOSAuthTokenActor for un-authed provider" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig
    ) {
      tokenActorProbe.expectNoMessage(2 seconds)
      subscriberProbe.expectMsgType[EventBusActor.EventBusFailure]
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

      EventBusActor.parseEvent[MarathonHealthStatusChange](ServerSentEvent(
        data = json_old,
        `type` = "health_status_changed_event"
      )) must beSome

      EventBusActor.parseEvent[MarathonHealthStatusChange](ServerSentEvent(
        data = json_new,
        `type` = "health_status_changed_event"
      )) must beSome
    }


  }

}
