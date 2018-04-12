package com.galacticfog.gestalt.dcos.marathon

import akka.actor.{Actor, ActorRef}
import akka.pattern.{ask, pipe}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.ServiceStatus._
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenError, DCOSAuthTokenResponse}
import com.galacticfog.gestalt.dcos.{LauncherConfig, ServiceInfo}
import javax.inject.{Inject, Named, Singleton}
import modules.WSClientFactory
import play.api.Logger
import play.api.http.HeaderNames
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

@Singleton
class RestClientActor @Inject()(launcherConfig: LauncherConfig,
                                wsFactory: WSClientFactory,
                                @Named(DCOSAuthTokenActor.name) authTokenActor: ActorRef ) extends Actor {

  import RestClientActor._
  import context.dispatcher

  private[this] val logger: Logger = Logger(this.getClass())

  private[this] val marathonBaseUrl = launcherConfig.marathon.baseUrl.stripSuffix("/")

  private[this] val appGroup = launcherConfig.marathon.appGroup
  private[this] val appIdWithGroup = s"/${appGroup}/(.*)".r

  private[this] val STATUS_UPDATE_TIMEOUT = 15.seconds

  private[this] def client: WSClient = wsFactory.getClient

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

  private[this] def genRequest(endpoint: String): Future[WSRequest] = {
    val fMaybeToken = getAuthToken
    val url = marathonBaseUrl + "/" + endpoint.stripPrefix("/")
    fMaybeToken map {
      _.foldLeft(client.url(url)){
        case (r,t) => r.addHttpHeaders(
          HeaderNames.AUTHORIZATION -> s"token=${t}"
        )
      }
    }
  }

  private[this] def launchApp(appPayload: MarathonAppPayload): Future[JsValue] = {
    val appId = appPayload.id.getOrElse(throw new RuntimeException("launchApp missing appId")).stripPrefix("/")
    for {
      req <- genRequest(s"/v2/apps")
      resp <- req.addQueryStringParameters("force" -> "true").post(
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

  private[this] def killApp(service: FrameworkService): Future[Boolean] = {
    logger.info(s"asking marathon to shut down ${service.name}")
    val appId = s"/${appGroup}/${service.name}"
    val endpoint = "/v2/apps" + appId
    for {
      req <- genRequest(endpoint)
      resp <- req.addQueryStringParameters("force" -> "true").delete()
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
      if (
        app.container.flatMap(_.docker).flatMap(_.network).contains("USER") ||
          app.networks.getOrElse(Seq.empty).flatMap(n => (n \ "mode").asOpt[String]).contains("container")
      ) {
        // ip-per-task: use first container IP we find
        val containerIp = task0.flatMap(_.ipAddresses.getOrElse(Seq.empty).collectFirst({
          case IPAddress(Some(ipaddress), _) => ipaddress
        }))
        val pmPorts = app.container.flatMap(_.docker).flatMap(_.portMappings)
          .orElse(app.container.flatMap(_.portMappings))
          .getOrElse(Seq.empty)
          .flatMap(_.containerPort)
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

  private[this] def getServices(): Future[Seq[ServiceInfo]] = {
    val endpoint = s"/v2/groups/${appGroup}"
    for {
      req <- genRequest(endpoint)
      resp <- req.addQueryStringParameters("embed" -> "group.apps", "embed" -> "group.apps.counts", "embed" -> "group.apps.tasks").withRequestTimeout(STATUS_UPDATE_TIMEOUT).get()
      svcs <- {
        considerInvalidatingAuthToken(resp.status)
        resp.status match {
          case 200 =>
            Future.fromTry(Try {
              (resp.json \ "apps").as[Seq[MarathonAppPayload]].flatMap {
                app => appIdWithGroup.unapplySeq(app.id.getOrElse("")) flatMap (_.headOption) flatMap (LauncherConfig.Services.fromName) map (service => (service, app))
              } map {
                case (service, app) => toServiceInfo(service, app)
              }
            })
          case 404 => Future.successful(Seq.empty)
          case _ => Future.failed(new RuntimeException(resp.statusText))
        }
      }
    } yield svcs
  }

  private[this] def getServiceStatus(service: FrameworkService): Future[ServiceInfo] = {
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

  override def receive: Receive = {
    case LaunchAppRequest(payload) => pipe(launchApp(payload)) to sender()
    case KillAppRequest(svc) => pipe(killApp(svc)) to sender()
    case GetServiceInfo(svc) => pipe(getServiceStatus(svc)) to sender()
    case GetAllServiceInfo => pipe(getServices()) to sender()
    case e =>
      val s = sender()
      logger.info(s"received unknown message ${e} from ${s}")
  }
}

object RestClientActor {

  trait Factory {
    def apply(): Actor
  }

  case class LaunchAppRequest(appPayload: MarathonAppPayload)
  case class GetServiceInfo(service: LauncherConfig.FrameworkService)
  case object GetAllServiceInfo
  case class KillAppRequest(service: LauncherConfig.FrameworkService)

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

}

