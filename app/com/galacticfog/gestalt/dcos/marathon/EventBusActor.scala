package com.galacticfog.gestalt.dcos.marathon

import akka.NotUsed
import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.headers.{Accept, Authorization, GenericHttpCredentials}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenError, DCOSAuthTokenResponse}
import com.google.inject.assistedinject.Assisted
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import javax.inject.{Inject, Named, Singleton}
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class EventBusActor @Inject()(launcherConfig: LauncherConfig,
                              @Named(DCOSAuthTokenActor.name) authTokenActor: ActorRef,
                              @Assisted subscriber: ActorRef,
                              @Assisted eventFilter: Seq[String] ) extends Actor with EventStreamUnmarshalling {

  import EventBusActor._
  import context.dispatcher

  override protected def maxLineSize: Int = launcherConfig.marathon.sseMaxLineSize
  override protected def maxEventSize: Int = launcherConfig.marathon.sseMaxEventSize

  private[this] val logger: Logger = Logger(this.getClass())

  private[this] val marathonBaseUrl = launcherConfig.marathon.baseUrl.stripSuffix("/")

  connectToBus(subscriber)

  private[this] def connectToBus(actorRef: ActorRef): Unit = {
    val fMaybeAuthToken = getAuthToken // start this early

    val sendToSubscriber = Sink.actorRef[MarathonEvent](actorRef, EventBusFailure("stream closed"))

    implicit val mat = ActorMaterializer()

    val fEventSource = for {
      maybeAuthToken <- fMaybeAuthToken
      req = maybeAuthToken.foldLeft(
        Get(s"${marathonBaseUrl}/v2/events").addHeader(Accept(MediaTypes.`text/event-stream`))
      ) {
        case (req, tok) =>
          logger.debug("adding header to event bus request")
          req.addHeader(
            Authorization(GenericHttpCredentials("token="+tok, ""))
          )
      }
      http = Http(context.system)
      resp <- http.singleRequest(
        connectionContext = if (launcherConfig.acceptAnyCertificate) {
          logger.warn("disabling certificate checking for connection to Marathon REST API, this is not recommended because it opens communications up to MITM attacks")
          val badSslConfig = AkkaSSLConfig(context.system).mapSettings(s => s.withLoose(s.loose.withDisableHostnameVerification(true)))
          http.createClientHttpsContext(badSslConfig)
        } else http.defaultClientHttpsContext,
        request = req
      )
    } yield resp

    // TODO: this is wrong... i need to receive these messages and manually forward them to the subscriber so that i can intercept the Closed event and die
    fEventSource.flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
      .onComplete {
        case Success(eventSource) =>
          logger.info("successfully attached to marathon event bus")
          actorRef ! ConnectedToEventBus
          eventSource
            .filter(_.eventType match {
              case Some(eventType) if !eventFilter.contains(eventType) =>
                logger.debug(s"filtered event with type '${eventType}'")
                false
              case None =>
                logger.debug("received server-sent-event heartbeat from marathon event bus")
                false
              case _ =>
                true
            })
            .collect {
              case sse @ ServerSentEvent(_, Some(MarathonAppTerminatedEvent.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonAppTerminatedEvent](sse)
              case sse @ ServerSentEvent(_, Some(MarathonStatusUpdateEvent.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonStatusUpdateEvent](sse)
              case sse @ ServerSentEvent(_, Some(MarathonDeploymentSuccess.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonDeploymentSuccess](sse)
              case sse @ ServerSentEvent(_, Some(MarathonDeploymentFailure.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonDeploymentFailure](sse)
              case sse @ ServerSentEvent(_, Some(MarathonHealthStatusChange.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonHealthStatusChange](sse)
            }
            .collect {
              case Some(obj) => obj
            }
            .to(sendToSubscriber)
            .run()
        case Failure(t) =>
          considerInvalidatingAuthToken(401)
          logger.info("failure connecting to marathon event bus", t)
          actorRef ! EventBusFailure("error connecting to Marathon event bus", Some(t))
      }
  }

  private[this] def getAuthToken: Future[Option[String]] = {
    launcherConfig.dcosAuth match {
      case None =>
        Future.successful(None)
      case Some(auth) =>
        implicit val timeout = akka.util.Timeout(5 seconds)
        val f = authTokenActor ? DCOSAuthTokenActor.DCOSAuthTokenRequest(
          dcosUrl = auth.login_endpoint,
          serviceAccountId = auth.uid,
          privateKey = auth.private_key
        )
        f.flatMap({
          case DCOSAuthTokenResponse(tok) => Future.successful(Some(tok))
          case DCOSAuthTokenError(msg)    => Future.failed(new RuntimeException(s"error retrieving ACS token: ${msg}"))
          case other                      => Future.failed(new RuntimeException(s"unexpected return from DCOSAuthTokenActor: $other"))
        })
    }
  }

  private[this] def considerInvalidatingAuthToken(status: Int): Unit = {
    if (Seq(401,403).contains(status)) {
      launcherConfig.dcosAuth foreach {
        _ => authTokenActor ! DCOSAuthTokenActor.InvalidateCachedToken
      }
    }
  }

  override def receive: Receive = {
    case e =>
      val s = sender()
      logger.info(s"received unknown message ${e} from ${s}")
  }
}

object EventBusActor {

  trait Factory {
    def apply(subscriber: ActorRef, eventFilter: Seq[String]): Actor
  }

  case object ConnectedToEventBus
  case class EventBusFailure(msg: String, t: Option[Throwable] = None)

  val logger: Logger = Logger(this.getClass())

  def parseEvent[T](event: ServerSentEvent)(implicit rds: play.api.libs.json.Reads[T]): Option[T] = {
    Option(event.data) filter {_.trim.nonEmpty} flatMap { data =>
      Try{Json.parse(data)} match {
        case Failure(e) =>
          logger.warn(s"error parsing event data as JSON:\n${data}", e)
          logger.warn(s"payload was:\n${data}")
          None
        case Success(js) =>
          js.validate[T] match {
            case JsError(_) =>
              logger.warn(s"error unmarshalling ${event.eventType} JSON")
              logger.warn(s"payload was:\n${data}")
              None
            case JsSuccess(obj, _) => Some(obj)
          }
      }
    }
  }

  implicit val formatSSE: OFormat[ServerSentEvent] = Json.format[ServerSentEvent]

}


