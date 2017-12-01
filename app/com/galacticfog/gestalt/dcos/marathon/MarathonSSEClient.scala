package com.galacticfog.gestalt.dcos.marathon

import java.security.cert.X509Certificate
import javax.inject.{Inject, Named, Singleton}
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

import scala.language.postfixOps
import akka.pattern.ask
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.http.scaladsl.model.headers.{Accept, Authorization, GenericHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.galacticfog.gestalt.dcos.{LauncherConfig, ServiceInfo}
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import de.heikoseeberger.akkasse.{EventStreamUnmarshalling, ServerSentEvent}
import akka.http.scaladsl.client.RequestBuilding.Get
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.ServiceStatus._
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenError, DCOSAuthTokenResponse}
import de.heikoseeberger.akkasse.MediaTypes.`text/event-stream`
import modules.WSClientFactory
import play.api.http.HeaderNames

object BiggerUnmarshalling extends EventStreamUnmarshalling {
  override protected def maxLineSize: Int = 524288
  override protected def maxEventSize: Int = 524288
}

@Singleton
class MarathonSSEClient @Inject() ( launcherConfig: LauncherConfig,
                                    wsFactory: WSClientFactory,
                                    @Named(DCOSAuthTokenActor.name) authTokenActor: ActorRef )
                                  ( implicit system: ActorSystem ) {

  import system.dispatcher
  import MarathonSSEClient._
  import BiggerUnmarshalling._

  val logger: Logger = Logger(this.getClass())

  private[this] val marathonBaseUrl = launcherConfig.marathon.baseUrl.stripSuffix("/")

  private[this] val appGroup = launcherConfig.marathon.appGroup
  private[this] val appIdWithGroup = s"/${appGroup}/(.*)".r

  private[this] val STATUS_UPDATE_TIMEOUT = 15.seconds

  private[marathon] def client: WSClient = wsFactory.getClient

  private[marathon] val connectionContext: HttpsConnectionContext = {
    if (launcherConfig.acceptAnyCertificate.contains(true)) {
      logger.warn("disabling certificate checking for connection to Marathon REST API, this is not recommended because it opens communications up to MITM attacks")
      // Create a trust manager that does not validate certificate chains
      val trustAllCerts: Array[TrustManager] = Array(new X509TrustManager {
        override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
        override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
        override def getAcceptedIssuers: Array[X509Certificate] = {null}
      })
      // Install the all-trusting trust manager
      val sc = SSLContext.getInstance("SSL")
      sc.init(null, trustAllCerts, new java.security.SecureRandom())
      new HttpsConnectionContext(sc, None, None, None, None, None)
    } else Http(system).defaultClientHttpsContext
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

  def connectToBus(actorRef: ActorRef): Unit = {
    val fMaybeAuthToken = getAuthToken // start this early
    implicit val mat = ActorMaterializer()
    val handler = Sink.actorRef(actorRef, akka.actor.Status.Failure(new RuntimeException("stream closed")))

    val fEventSource = for {
      maybeAuthToken <- fMaybeAuthToken
      req = maybeAuthToken.foldLeft(
        Get(s"${marathonBaseUrl}/v2/events").addHeader(Accept(`text/event-stream`))
      ) {
        case (req, tok) =>
          logger.debug("adding header to event bus request")
          req.addHeader(
            Authorization(GenericHttpCredentials("token="+tok, ""))
          )
      }
      resp <- Http(system).singleRequest(
        connectionContext = connectionContext,
        request = req
      )
    } yield resp

    fEventSource.flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
      .onComplete {
        case Success(eventSource) =>
          logger.info("successfully attached to marathon event bus")
          actorRef ! Connected
          eventSource.runWith(handler)
        case Failure(t) =>
          considerInvalidatingAuthToken(401)
          logger.info("failure connecting to marathon event bus", t)
          actorRef ! akka.actor.Status.Failure(new RuntimeException("error connecting to Marathon event bus", t))
      }
  }

  private[this] def genRequest(endpoint: String): Future[WSRequest] = {
    val fMaybeToken = getAuthToken
    val url = marathonBaseUrl + "/" + endpoint.stripPrefix("/")
    fMaybeToken map {
      _.foldLeft(client.url(url)){
        case (r,t) => r.withHeaders(
          HeaderNames.AUTHORIZATION -> s"token=${t}"
        )
      }
    }
  }

  def launchApp(appPayload: MarathonAppPayload): Future[JsValue] = {
    val appId = appPayload.id.getOrElse(throw new RuntimeException("launchApp missing appId")).stripPrefix("/")
    for {
      req <- genRequest(s"/v2/apps")
      resp <- req.withQueryString("force" -> "true").post(
        Json.toJson(appPayload)
      )
      json <- {
        considerInvalidatingAuthToken(resp.status)
        resp.status match {
          case 200 | 201 | 409 =>
            if (resp.status == 409) logger.warn(s"launchApp(${appId}) response is 409: ${resp.body}, will ignore")
            Future.successful(resp.json)
          case s =>
            logger.info(s"launchApp(${appId}) response: ${resp.status}:${resp.statusText}")
            Future.failed(new RuntimeException(
              Try {
                (resp.json \ "message").as[String]
              } getOrElse resp.body
            ))
        }
      }
    } yield json
  }

  def killApp(service: FrameworkService): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${service.name}")
    val appId = s"/${appGroup}/${service.name}"
    val endpoint = "/v2/apps" + appId
    for {
      req <- genRequest(endpoint)
      resp <- req.withQueryString("force" -> "true").delete()
      deleted <- {
        logger.info(s"marathon.delete(${service.name}) => ${resp.statusText}")
        considerInvalidatingAuthToken(resp.status)
        resp.status match {
          case 200 => Future.successful(true)
          case 404 => Future.successful(false)
          case _   => Future.failed(new RuntimeException(s"marathon.delete(${appId}) failed with ${resp.status}/${resp.body}"))
        }
      }
    } yield deleted
  }

  def stopApp(svcName: String): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${svcName}")
    val endpoint = "/v2/apps/${appGroup}/${svcName}"
    for {
      req <- genRequest(endpoint)
      resp <- req.withQueryString("force" -> "true").put(Json.obj("instances" -> 0))
      maybeOk = {
        logger.info(s"marathon.stop(${svcName}) => ${resp.statusText}")
        considerInvalidatingAuthToken(resp.status)
        resp.status == 200
      }
    } yield maybeOk
  }

  private[marathon] def toServiceInfo(service: FrameworkService, app: MarathonAppPayload): ServiceInfo = {
    val staged  = app.tasksStaged.get
    val running = app.tasksRunning.get
    val healthy = app.tasksHealthy.get
    val sickly  = app.tasksUnhealthy.get
    val target  = app.instances.getOrElse(0)

    val status = if (staged != 0) STAGING
    else if (target != running) WAITING
    else if (target == 0) STOPPED
    else if (sickly > 0) UNHEALTHY
    else if (target == healthy) HEALTHY
    else RUNNING

    val lbExposed = app.labels.exists(_.filterKeys(_.matches("HAPROXY_GROUP")).nonEmpty)

    val HAPROXY_N_ENABLED = "HAPROXY_([0-9]+)_ENABLED".r
    val lbDisabledPortIndices = app.labels.getOrElse(Map.empty).collect({
      case (HAPROXY_N_ENABLED(portNumber),v) if v == "false" => portNumber.toInt
    }).toSet

    val serviceEndpoints = launcherConfig.marathon.marathonLbUrl match {
      case Some(lbUrl) if lbExposed =>
        app.portDefinitions
          .getOrElse(Seq.empty)
          .map(_.port)
          .zipWithIndex
          .collect({
            case (Some(portNumber), index) if !lbDisabledPortIndices.contains(index) => portNumber
          })
          .map(lbUrl + ":" + _)
      case _ => Seq.empty
    }

    val vhosts = getVHosts(app)

    // there ideally is one health task. may be multiple tasks if:
    // - they're all healthy (in which case, the first is as good as any)
    // - unhealthy one is being killed for a new one, in which case, maybe we'll eventually converge
    // (TODO: could put in logic to pick newest one or healthy one if we need to)
    val task0 = app.tasks.flatMap(_.headOption)
    val (hostname,ports) = {
      if ( app.container.flatMap(_.docker).flatMap(_.network).contains("USER") ) {
        // ip-per-task: use first container IP we find
        val containerIp = task0.flatMap(_.ipAddresses.getOrElse(Seq.empty).collectFirst({
          case IPAddress(Some(ipaddress), _) => ipaddress
        }))
        val pmPorts = app.container.flatMap(_.docker).flatMap(_.portMappings).getOrElse(Seq.empty).flatMap(_.containerPort)
        ( containerIp,
          pmPorts )
      } else {
        // use host ip and host port mapping
        ( task0.flatMap(_.host),
          task0.flatMap(_.ports).getOrElse(Seq.empty) )
      }
    }

    ServiceInfo(service,vhosts ++ serviceEndpoints,hostname,ports.map(_.toString),status)
  }

  def getServices(): Future[Seq[ServiceInfo]] = {
    val endpoint = s"/v2/groups/${appGroup}"
    for {
      req <- genRequest(endpoint)
      resp <- req.withQueryString("embed" -> "group.apps", "embed" -> "group.apps.counts", "embed" -> "group.apps.tasks").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get()
      svcs <- {
        considerInvalidatingAuthToken(resp.status)
        resp.status match {
          case 200 =>
            Future.fromTry(Try {
              (resp.json \ "apps").as[Seq[MarathonAppPayload]].flatMap {
                app => appIdWithGroup.unapplySeq(app.id.getOrElse("")) flatMap (_.headOption) flatMap (LauncherConfig.Services.fromName) map (service => (service, app))
              } map { case (service, app) => toServiceInfo(service, app) }
            })
          case 404 => Future.successful(Seq.empty)
          case _ => Future.failed(new RuntimeException(resp.statusText))
        }
      }
    } yield svcs
  }

  def getServiceStatus(service: FrameworkService): Future[ServiceInfo] = {
    val endpoint = s"/v2/apps/${appGroup}/${service.name}"
    val fStat = for {
      req <- genRequest(endpoint)
      resp <- req.withRequestTimeout(STATUS_UPDATE_TIMEOUT).get()
      stat <- {
        considerInvalidatingAuthToken(resp.status)
        resp.status match {
          case 200 =>
            Future.fromTry(Try{
              val app = (resp.json \ "app").as[MarathonAppPayload]
              toServiceInfo(service, app)
            })
          case 404 => Future.successful(ServiceInfo(service,Seq(),None,Seq.empty,NOT_FOUND))
          case _   =>
            Future.failed(new RuntimeException(resp.statusText))
        }
      }
    } yield stat
    fStat recover {
      case e =>
        logger.error("error retrieving app from Marathon API",e)
        ServiceInfo(service, Seq(),None,Seq.empty, NOT_FOUND)
    }
  }

}

object MarathonSSEClient {

  case object Connected

  def getVHosts(app: MarathonAppPayload): Seq[String] = {
    lazy val HAPROXY_N_VHOST = "HAPROXY_([0-9]+)_VHOST".r
    lazy val HAPROXY_N_VPATH = "HAPROXY_([0-9]+)_PATH".r
    lazy val HAPROXY_N_ENABLED = "HAPROXY_([0-9]+)_ENABLED".r
    if ( app.labels.flatMap(_.get("HAPROXY_GROUP")).filter(_.trim.nonEmpty).isDefined ) {
      val vhosts = app.labels.getOrElse(Map.empty).collect({
        case (HAPROXY_N_VHOST(index), vhost) => (index.toInt -> vhost)
      })
      val vpaths = app.labels.getOrElse(Map.empty).collect({
        case (HAPROXY_N_VPATH(index), vpath) => (index.toInt -> vpath)
      })
      val disabled = app.labels.getOrElse(Map.empty).collect({
        case (HAPROXY_N_ENABLED(index), enabled) if ! Set("t","true","yes","y").contains(enabled.toLowerCase) => index.toInt
      }).toSet
      // don't care about vpaths for which there is no corresponding vhost
      vhosts.collect({
        case (portIndex, vhost) if !disabled(portIndex) => s"https://$vhost" + vpaths.getOrElse(portIndex,"")
      }).toSeq
    }
    else Seq.empty
  }

  val logger: Logger = Logger(this.getClass())

  def parseEvent[T](event: ServerSentEvent)(implicit rds: play.api.libs.json.Reads[T]): Option[T] = {
    event.data filter {_.trim.nonEmpty} flatMap { data =>
      Try{Json.parse(data)} match {
        case Failure(e) =>
          logger.warn(s"error parsing event data as JSON:\n${data}", e)
          logger.warn(s"payload was:\n${data}")
          None
        case Success(js) =>
          js.validate[T] match {
            case JsError(_) =>
              logger.warn(s"error unmarshalling ${event.`type`} JSON")
              logger.warn(s"payload was:\n${data}")
              None
            case JsSuccess(obj, _) => Some(obj)
          }
      }
    }
  }

  implicit val formatSSE: OFormat[ServerSentEvent] = Json.format[ServerSentEvent]

}


