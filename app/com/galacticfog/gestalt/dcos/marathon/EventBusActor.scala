package com.galacticfog.gestalt.dcos.marathon

import java.security.cert.X509Certificate

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.headers.{Accept, Authorization, GenericHttpCredentials}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MediaTypes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.galacticfog.gestalt.dcos.LauncherConfig
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenError, DCOSAuthTokenResponse}
import com.google.inject.assistedinject.Assisted
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import javax.inject.{Inject, Named}
import javax.net.ssl.{KeyManager, SSLContext, X509TrustManager}
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class EventBusActor @Inject()(launcherConfig: LauncherConfig,
                              @Named(DCOSAuthTokenActor.name) authTokenActor: ActorRef,
                              httpResponder: EventBusActor.HttpResponder,
                              @Assisted subscriber: ActorRef,
                              @Assisted eventFilter: Option[Seq[String]] ) extends Actor with EventStreamUnmarshalling {

  import EventBusActor._
  import context.dispatcher

  override protected def maxLineSize: Int = launcherConfig.marathon.sseMaxLineSize
  override protected def maxEventSize: Int = launcherConfig.marathon.sseMaxEventSize

  private[this] val logger: Logger = Logger(this.getClass())

  private[this] val marathonBaseUrl = launcherConfig.marathon.baseUrl.stripSuffix("/")

  connectToBus

  private[this] def connectToBus(): Unit = {
    val fMaybeAuthToken = getAuthToken // start this early

    val sendToSelf = Sink.actorRef[MarathonEvent](self, EventBusFailure("stream closed"))

    implicit val mat = ActorMaterializer()

    val fEventSource = for {
      maybeAuthToken <- fMaybeAuthToken
      uri = eventFilter.foldLeft(
        Uri(s"${marathonBaseUrl}/v2/events")
      ){ case (u,filters) =>
        val q = filters.foldLeft(Uri.Query()) {
          case (flt, eventType) => flt.+:("event_type" -> eventType)
        }
        u.withQuery(q)
      }
      req = maybeAuthToken.foldLeft(
        Get(uri).addHeader(Accept(MediaTypes.`text/event-stream`))
      ) {
        case (r, tok) =>
          logger.debug("adding header to event bus request")
          r.addHeader(
            Authorization(GenericHttpCredentials("token="+tok, ""))
          )
      }
      resp <- httpResponder(req)(context.system)
    } yield resp

    fEventSource.flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
      .onComplete {
        case Success(eventSource) =>
          logger.info("successfully attached to marathon event bus")
          self ! ConnectedToEventBus
          eventSource
            .filter(_.eventType match {
              case Some(eventType) if eventFilter.exists(_.contains(eventType) == false) =>
                logger.debug(s"filtered Marathon event of type '${eventType}'")
                false
              case None =>
                logger.debug("received SSE heartbeat from Marathon event bus, filtering")
                false
              case _ =>
                true
            })
            .collect {
              case sse @ ServerSentEvent(_, Some(MarathonStatusUpdateEvent.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonStatusUpdateEvent](sse)
              case sse @ ServerSentEvent(_, Some(MarathonDeploymentSuccess.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonDeploymentSuccess](sse)
              case sse @ ServerSentEvent(_, Some(MarathonDeploymentFailure.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonDeploymentFailure](sse)
              case sse @ ServerSentEvent(_, Some(MarathonHealthStatusChange.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonHealthStatusChange](sse)
              case sse @ ServerSentEvent(_, Some(MarathonAppTerminatedEvent.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonAppTerminatedEvent](sse)
              case sse @ ServerSentEvent(_, Some(MarathonDeploymentInfo.eventType), _, _) =>
                EventBusActor.parseEvent[MarathonDeploymentInfo](sse)
            }
            .collect {
              // None means a parsing failure above
              case Some(obj) => obj
            }
            .to(sendToSelf)
            .run()
        case Failure(t) =>
          considerInvalidatingAuthToken(401)
          logger.info("failure connecting to marathon event bus", t)
          self ! EventBusFailure("error connecting to Marathon event bus", Some(t))
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
    case marEvent: MarathonEvent =>
      logger.debug(s"sending MarathonEvent of type '${marEvent.eventType}' to subscriber")
      subscriber ! marEvent
    case ConnectedToEventBus =>
      logger.info("connected to event bus")
      subscriber ! ConnectedToEventBus
    case f @ EventBusFailure(msg, maybeT) =>
      maybeT match {
        case Some(_) =>
          logger.warn(s"event bus error: $msg")
        case None =>
          logger.warn(s"event bus error: $msg")
      }
      subscriber ! f // let the subscriber know there is a problem
      throw f        // go ahead and fail, since dead event bus means we have no purpose
  }
}

object EventBusActor {

  val logger: Logger = Logger(this.getClass())

  trait Factory {
    def apply(subscriber: ActorRef, eventFilter: Option[Seq[String]]): Actor
  }

  trait HttpResponder {
    def apply(req: HttpRequest)(implicit system: ActorSystem): Future[HttpResponse]
  }

  class DefaultHttpResponder @Inject()(launcherConfig: LauncherConfig) extends HttpResponder {
    lazy private val trustfulSslContext: SSLContext = {
      object NoCheckX509TrustManager extends X509TrustManager {
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()

        override def getAcceptedIssuers = Array[X509Certificate]()
      }
      val context = SSLContext.getInstance("TLS")
      context.init(Array[KeyManager](), Array(NoCheckX509TrustManager), null)
      context
    }

    override def apply(req: HttpRequest)
                      (implicit system: ActorSystem): Future[HttpResponse] = {
      val http = Http()
      http.singleRequest(
        request = req,
        connectionContext = if (launcherConfig.acceptAnyCertificate) {
          logger.warn("disabling certificate checking for connection to Marathon REST API; this is not recommended because it opens communications up to MITM attacks")
          val badSslConfig: AkkaSSLConfig = AkkaSSLConfig().mapSettings(s =>
            s.withLoose(
              s.loose
                .withDisableHostnameVerification(value = true)
                .withAcceptAnyCertificate(value = true)
            )
          )
          val ctx = http.createClientHttpsContext(badSslConfig)
          new HttpsConnectionContext(
            trustfulSslContext,
            ctx.sslConfig,
            ctx.enabledCipherSuites,
            ctx.enabledProtocols,
            ctx.clientAuth,
            ctx.sslParameters
          )
        } else http.defaultClientHttpsContext
      )
    }
  }



  case object ConnectedToEventBus
  case class EventBusFailure(msg: String, t: Option[Throwable] = None) extends RuntimeException

  sealed trait MarathonEvent {
    def eventType: String
  }

  case class MarathonStatusUpdateEvent( slaveId: String,
                                        taskId: String,
                                        taskStatus: String,
                                        message: String,
                                        appId: String,
                                        host: String,
                                        ports: Seq[Int],
                                        ipAddresses: Option[Seq[IPAddress]],
                                        version: String,
                                        timestamp: String) extends MarathonEvent {
    val eventType = MarathonStatusUpdateEvent.eventType
  }
  case object MarathonStatusUpdateEvent {
    val eventType = "status_update_event"
  }

  case class MarathonDeploymentSuccess( timestamp: String,
                                        id: String) extends MarathonEvent {
    val eventType = MarathonDeploymentSuccess.eventType
  }
  case object MarathonDeploymentSuccess extends {
    val eventType = "deployment_success"
  }

  case class MarathonDeploymentFailure( timestamp: String,
                                        id: String) extends MarathonEvent {
    val eventType = MarathonDeploymentFailure.eventType
  }
  case object MarathonDeploymentFailure {
    val eventType = "deployment_failed"
  }

  case class MarathonHealthStatusChange( timestamp: String,
                                         appId: String,
                                         taskId: Option[String],
                                         instanceId: Option[String],
                                         version: String,
                                         alive: Boolean) extends MarathonEvent {
    val eventType = MarathonHealthStatusChange.eventType
  }
  case object MarathonHealthStatusChange {
    val eventType = "health_status_changed_event"
  }

  case class MarathonAppTerminatedEvent( appId: String,
                                         timestamp: String ) extends MarathonEvent {
    val eventType = MarathonAppTerminatedEvent.eventType
  }
  case object MarathonAppTerminatedEvent {
    val eventType = "app_terminated_event"
  }

  case class MarathonDeploymentInfo( currentStep: MarathonDeploymentInfo.Step,
                                     eventType: String,
                                     timestamp: String) extends MarathonEvent
  case object MarathonDeploymentInfo {
    val eventType = "deployment_info"

    case class Step(actions: Seq[Step.Action])

    case object Step {
      case class Action(action: String, app: String)
    }
  }

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

  implicit val statusUpdateEventRead: Reads[MarathonStatusUpdateEvent] = Json.reads[MarathonStatusUpdateEvent]
  implicit val deploymentSuccessRead: Reads[MarathonDeploymentSuccess] = Json.reads[MarathonDeploymentSuccess]
  implicit val deploymentFailureRead: Reads[MarathonDeploymentFailure] = Json.reads[MarathonDeploymentFailure]
  implicit val healthStatusChangedRead: Reads[MarathonHealthStatusChange] = Json.reads[MarathonHealthStatusChange]
  implicit val appTerminatedEventRead: Reads[MarathonAppTerminatedEvent] = Json.reads[MarathonAppTerminatedEvent]
  implicit val deploymentStepActions: Reads[MarathonDeploymentInfo.Step.Action] = Json.reads[MarathonDeploymentInfo.Step.Action]
  implicit val deploymentStep: Reads[MarathonDeploymentInfo.Step] = Json.reads[MarathonDeploymentInfo.Step]
  implicit val deploymentInfo: Reads[MarathonDeploymentInfo] = Json.reads[MarathonDeploymentInfo]

}


