package com.galacticfog.gestalt.dcos.marathon

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenRequest, DCOSAuthTokenResponse}
import com.galacticfog.gestalt.dcos.marathon.EventBusActor.{MarathonAppTerminatedEvent, MarathonDeploymentSuccess, MarathonEvent, MarathonHealthStatusChange}
import mockws.{MockWS, MockWSHelpers}
import org.specs2.mock.Mockito
import play.api.test._

import scala.concurrent.Future
import scala.concurrent.duration._

class DummySupervisor extends Actor with ActorLogging {
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: RuntimeException =>
      log.info("stopping child actor in test")
      Stop
  }
  override def receive: Receive = { case _ => }
}

class EventBusActorSpecs extends PlaySpecification with Mockito with MockWSHelpers with TestHelper {

  abstract class WithMarSSEConfig( config: Seq[(String,Any)] = Seq.empty,
                                   routes: MockWS.Routes = PartialFunction.empty,
                                   response: String = "",
                                   eventFilter: Option[Seq[String]] = None
                                 )
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
    val mockEventStream = mock[EventBusActor.DefaultHttpResponder]
    mockEventStream.apply(any)(any) returns Future.successful(HttpResponse(
      entity = HttpEntity(MediaTypes.`text/event-stream`, response.getBytes)
    ))
    val busActor = TestActorRef(Props(new EventBusActor(
      injector.instanceOf[LauncherConfig],
      tokenActorProbe.ref,
      mockEventStream,
      subscriberProbe.ref,
      eventFilter
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
      subscriberProbe.expectMsg(EventBusActor.ConnectedToEventBus)
      subscriberProbe.expectMsg(EventBusActor.EventBusFailure("stream closed"))
      there was one(mockEventStream).apply(argThat(
        (req: HttpRequest) => req.headers.exists(h => h.name() == "Authorization" && h.value() == s"token=${authToken}")
      ))(any)
    }

    "not request auth token from DCOSAuthTokenActor for un-authed provider" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig
    ) {
      tokenActorProbe.expectNoMessage(2 seconds)
      subscriberProbe.expectMsg(EventBusActor.ConnectedToEventBus)
      subscriberProbe.expectMsg(EventBusActor.EventBusFailure("stream closed"))
      there was one(mockEventStream).apply(argThat(
        (req: HttpRequest) => !req.headers.exists(h => h.name() == "Authorization")
      ))(any)
    }

    "abide the filter in the query and the forwarded messages" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      response = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/marathon-event-stream.txt")).getLines().mkString("\n"),
      eventFilter = Some(Seq(MarathonDeploymentSuccess.eventType, MarathonAppTerminatedEvent.eventType))
    ) {
      subscriberProbe.expectMsg(EventBusActor.ConnectedToEventBus)
      subscriberProbe.expectMsgType[MarathonDeploymentSuccess]
      subscriberProbe.expectMsgType[MarathonDeploymentSuccess]
      subscriberProbe.expectMsgType[MarathonAppTerminatedEvent]
      subscriberProbe.expectMsg(EventBusActor.EventBusFailure("stream closed"))
      there was one(mockEventStream).apply(
        ((_: HttpRequest).uri.rawQueryString) ^^ beSome(
          contain(s"event_type=${MarathonDeploymentSuccess.eventType}")
            and
          contain(s"event_type=${MarathonAppTerminatedEvent.eventType}")
        )
      )(any)
    }

    "empty filter sends everything" in new WithMarSSEConfig(
      config = TestConfigs.noAuthConfig ++ TestConfigs.marathonConfig,
      response = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/marathon-event-stream.txt")).getLines().mkString("\n"),
      eventFilter = None
    ) {
      subscriberProbe.expectMsg(EventBusActor.ConnectedToEventBus)
      subscriberProbe.receiveN(8).map {
        _.asInstanceOf[MarathonEvent].eventType
      } must containTheSameElementsAs(Seq(
        "deployment_info",
        "status_update_event",
        "deployment_success",
        "deployment_info",
        "status_update_event",
        "status_update_event",
        "deployment_success",
        "app_terminated_event"
      ))
      subscriberProbe.expectMsg(EventBusActor.EventBusFailure("stream closed"))
      there was one(mockEventStream).apply(
        ((_: HttpRequest).uri.rawQueryString) ^^ beNone
      )(any)
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
