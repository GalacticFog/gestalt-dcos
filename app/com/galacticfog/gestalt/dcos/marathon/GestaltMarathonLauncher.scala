package com.galacticfog.gestalt.dcos.marathon

import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM}
import com.galacticfog.gestalt.dcos.{marathon, GestaltTaskFactory}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Try}
import com.galacticfog.gestalt.dcos.marathon._
import akka.pattern.ask

sealed trait LauncherState {
  def targetService: Option[String] = None
}
// in order...

case object Uninitialized       extends LauncherState
case object LaunchingDB         extends LauncherState {override def targetService = Some("data")}
case object LaunchingRabbit     extends LauncherState {override def targetService = Some("rabbit")}
case object LaunchingSecurity   extends LauncherState {override def targetService = Some("security")}
case object RetrievingAPIKeys   extends LauncherState
case object LaunchingKong       extends LauncherState {override def targetService = Some("kong")}
case object LaunchingApiGateway extends LauncherState {override def targetService = Some("api-gateway")}
case object LaunchingLambda     extends LauncherState {override def targetService = Some("lambda")}
case object LaunchingMeta       extends LauncherState {override def targetService = Some("meta")}
case object BootstrappingMeta   extends LauncherState
case object SyncingMeta         extends LauncherState
case object ProvisioningMetaProviders  extends LauncherState
case object LaunchingApiProxy   extends LauncherState {override def targetService = Some("api-proxy")}
case object LaunchingUI         extends LauncherState {override def targetService = Some("ui")}
case object LaunchingPolicy     extends LauncherState {override def targetService = Some("policy")}
case object AllServicesLaunched extends LauncherState
// failure
case object ShuttingDown        extends LauncherState
case object Error               extends LauncherState

final case class ServiceData(urls: Map[String,String],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String],
                             errorStage: Option[String])
case object ServiceData {
  def empty: ServiceData = ServiceData(Map.empty,None,None,None)
}

case object StatusRequest
case object LaunchServicesRequest
case object ShutdownRequest
case object ShutdownAcceptedResponse
case object RetryRequest
final case class ErrorEvent(message: String, errorStage: Option[String])
final case class SecurityInitializationComplete(key: GestaltAPIKey)
case object APIKeyTimeout
case object MetaBootstrapFinished
case object MetaBootstrapTimeout
case object MetaSyncFinished
case object MetaSyncTimeout
case object MetaProvidersProvisioned
case object MetaProviderTimeout

final case class StatusResponse(launcherStage: String, error: Option[String])

object GestaltMarathonLauncher {
  val LAUNCH_ORDER: Seq[LauncherState] = Seq(
    LaunchingDB, LaunchingRabbit,
    LaunchingSecurity, RetrievingAPIKeys,
    LaunchingKong, LaunchingApiGateway,
    LaunchingLambda,
    LaunchingMeta, BootstrappingMeta, SyncingMeta, ProvisioningMetaProviders,
    LaunchingPolicy,
    LaunchingApiProxy, LaunchingUI,
    AllServicesLaunched
  )
}

class GestaltMarathonLauncher @Inject()(config: Configuration,
                                        marClient: MarathonSSEClient,
                                        wsclient: WSClient,
                                        gtf: GestaltTaskFactory) extends LoggingFSM[LauncherState,ServiceData] {

  import GestaltMarathonLauncher._

  def getString(path: String, default: String): String = config.getString(path).getOrElse(default)

  def getInt(path: String, default: Int): Int = config.getInt(path).getOrElse(default)

  def sendMessageToSelf[A](delay: FiniteDuration, message: A) = {
    this.context.system.scheduler.scheduleOnce(delay, self, message)
  }

  implicit val apiKeyReads = Json.format[GestaltAPIKey]

  val marathonBaseUrl = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"

  val appGroup = getString("marathon.appGroup", GestaltTaskFactory.DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/")

  val tld = config.getString("marathon.tld").map(tld => Json.obj("tld" -> tld)).getOrElse(Json.obj())

  val VIP = config.getString("service.vip") getOrElse "10.10.10.10"

  // setup a-priori/static globals
  val globals = Json.obj(
    "marathon" -> Json.obj(
      "appGroup" -> appGroup
    ).++(tld),
    "database" -> Json.obj(
      "hostname" -> getString("database.hostname", VIP),
      "port" -> getInt("database.port", 5432),
      "username" -> getString("database.username", "gestaltdev"),
      "password" -> getString("database.password", "letmein"),
      "prefix" -> getString("database.prefix", "gestalt-")
    )
  )

  val securityCredentials = Json.obj(
    "username" -> getString("security.username","gestalt-admin")
  ) ++ config.getString("security.password").map(p => Json.obj("password" -> p)).getOrElse(Json.obj())

  private def launchApp(name: String, apiKey: Option[GestaltAPIKey] = None): Unit = {
    val currentState = nextStateData.toString
    val allConfig = apiKey.map(apiKey => Json.obj(
      "security" -> Json.obj(
        "apiKey" -> apiKey.apiKey,
        "apiSecret" -> apiKey.apiSecret.get
      )
    )).fold(globals)(sec => sec ++ globals)
    val payload = gtf.getMarathonPayload(name, allConfig)
    log.debug("'{}' launch payload:\n{}", name, Json.prettyPrint(Json.toJson(payload)))
    val fLaunch = marClient.launchApp(payload) map {
      r => log.debug("'{}' launch response: {}", name, r.toString)
    }
    // launch failed, so we'll never get a task update
    fLaunch.onFailure {
      case e: Throwable =>
        log.warning("error launching {}: {}",name,e.getMessage)
        sendMessageToSelf(0.seconds, ErrorEvent(e.getMessage,errorStage = Some(currentState)))
    }
  }

  private def nextState(state: LauncherState): LauncherState = {
    val cur = LAUNCH_ORDER.indexOf(state)
    if (LAUNCH_ORDER.isDefinedAt(cur+1)) LAUNCH_ORDER(cur+1) else Error
  }

  private def standardWhen(state: LauncherState) = when(state) {
    case Event(e @ MarathonStatusUpdateEvent(_, _, "TASK_RUNNING", _, appId, host, ports, _, _, _, _) , d) if appId == s"/${appGroup}/${state.targetService.get}" =>
      val srvName = state.targetService.get
      log.info(s"${srvName} running")
      val newData = ports.headOption match {
        case Some(port) => d.copy(
          urls = d.urls + (srvName -> s"${host}:${port}")
        )
        case None => d
      }
      goto(nextState(state)) using newData
  }

  startWith(Uninitialized, ServiceData.empty)

  when(Uninitialized) {
    case Event(LaunchServicesRequest,d) =>
      goto(LAUNCH_ORDER.headOption getOrElse AllServicesLaunched)
  }

  // service launch stages
  onTransition {
    case _ -> stage if stage.targetService.isDefined => launchApp(stage.targetService.get, nextStateData.adminKey)
  }

  // post-launch stages
  onTransition {
    case _ -> RetrievingAPIKeys =>
      nextStateData.urls.get(LaunchingSecurity.targetService.get) match {
        case None => sendMessageToSelf(0.seconds, ErrorEvent("while initializing security, missing security URL after launching security", Some(RetrievingAPIKeys.toString)))
        case Some(secUrl) =>
          val initUrl = s"http://${secUrl}/init"
          log.info(s"initializing security at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).post(securityCredentials) flatMap { resp =>
            log.info("security.init response: {}",resp.toString)
            resp.status match {
              case 200 =>
                Try{resp.json.as[Seq[GestaltAPIKey]].head} match {
                  case Success(key) =>
                    Future.successful(SecurityInitializationComplete(key))
                  case Failure(e) =>
                    Future.failed(new RuntimeException("while initializing security, error extracting API key form security initialization response"))
                }
              case not200 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(msg) =>
              sendMessageToSelf(0.seconds, msg)
            case Failure(ex) =>
              log.warning("error initializing security service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> BootstrappingMeta =>
      (nextStateData.urls.get(LaunchingMeta.targetService.get),nextStateData.adminKey) match {
        case (None,_) => sendMessageToSelf(0.seconds, ErrorEvent("while bootstrapping meta, missing meta URL after launching meta", Some(BootstrappingMeta.toString)))
        case (_,None) => sendMessageToSelf(0.seconds, ErrorEvent("while bootstrapping meta, missing admin API key after initializing security", Some(BootstrappingMeta.toString)))
        case (Some(metaUrl),Some(apiKey)) =>
          val initUrl = s"http://${metaUrl}/bootstrap"
          log.info(s"bootstrapping meta at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post("") flatMap { resp =>
            log.info("meta.bootstrap response: {}",resp.toString)
            resp.status match {
              case 204 =>
                Future.successful(MetaBootstrapFinished)
              case not200 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(msg) =>
              sendMessageToSelf(0.seconds, msg)
            case Failure(ex) =>
              log.warning("error bootstrapping meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> SyncingMeta =>
      (nextStateData.urls.get(LaunchingMeta.targetService.get),nextStateData.adminKey) match {
        case (None,_) => sendMessageToSelf(0.seconds, ErrorEvent("while syncing meta, missing meta URL after launching meta", Some(SyncingMeta.toString)))
        case (_,None) => sendMessageToSelf(0.seconds, ErrorEvent("while syncing meta, missing admin API key after initializing security", Some(SyncingMeta.toString)))
        case (Some(metaUrl),Some(apiKey)) =>
          val initUrl = s"http://${metaUrl}/sync"
          log.info(s"syncing meta at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post("") flatMap { resp =>
            log.info("meta.sync response: {}",resp.toString)
            resp.status match {
              case 204 =>
                Future.successful(MetaSyncFinished)
              case not200 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(msg) =>
              sendMessageToSelf(0.seconds, msg)
            case Failure(ex) =>
              log.warning("error syncing meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> ProvisioningMetaProviders =>
      (nextStateData.urls.get(LaunchingMeta.targetService.get),nextStateData.adminKey) match {
        case (None,_) => sendMessageToSelf(0.seconds, ErrorEvent("while provisioning providers, missing meta URL after launching meta", Some(SyncingMeta.toString)))
        case (_,None) => sendMessageToSelf(0.seconds, ErrorEvent("while provisioning providers, missing admin API key after initializing security", Some(SyncingMeta.toString)))
        case (Some(metaUrl),Some(apiKey)) =>
          val initUrl = s"http://${metaUrl}/root/providers"
          val json = Json.parse(
            s"""
              |{
              |  "description": "",
              |  "resource_type": "Gestalt::Configuration::Provider::Marathon",
              |  "properties": {
              |    "environments": [],
              |    "config": {
              |      "auth": {
              |        "scheme": "Basic",
              |        "username": "username",
              |        "password": "password"
              |      },
              |      "url": "${marathonBaseUrl}",
              |      "networks": [
              |        {
              |          "name": "HOST"
              |        },
              |        {
              |          "name": "BRIDGE"
              |        }
              |      ],
              |      "extra": {}
              |    },
              |    "locations": [
              |      {
              |        "name": "dcos",
              |        "enabled": true
              |      }
              |    ]
              |  },
              |  "name": "base-marathon"
              |}
            """.stripMargin)
          log.info(s"provisioning provider in meta at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post(json) flatMap { resp =>
            log.info("meta.provision response: {}",resp.toString)
            resp.status match {
              case 201 =>
                Future.successful(MetaProvidersProvisioned)
              case not201 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(msg) =>
              sendMessageToSelf(0.seconds, msg)
            case Failure(ex) =>
              log.warning("error provisioning providers in meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
  }

  LAUNCH_ORDER.filter(_.targetService.isDefined).foreach(standardWhen)

  onTransition {
    case _ -> RetrievingAPIKeys => sendMessageToSelf(5.minutes, APIKeyTimeout)
    case _ -> BootstrappingMeta => sendMessageToSelf(5.minutes, MetaBootstrapTimeout)
    case _ -> SyncingMeta       => sendMessageToSelf(5.minutes, MetaSyncTimeout)
    case _ -> ProvisioningMetaProviders => sendMessageToSelf(5.minutes, MetaProviderTimeout)
  }

  when(RetrievingAPIKeys) {
    case Event(RetryRequest, d) =>
      goto(RetrievingAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
      log.debug("received apiKey:\n{}",Json.prettyPrint(Json.toJson(apiKey)))
      goto(nextState(stateName)) using d.copy(
        adminKey = Some(apiKey)
      )
    case Event(APIKeyTimeout, d) =>
      val mesg = "timed out waiting for initialization of gestalt-security and retrieval of administrative API keys"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(BootstrappingMeta) {
    case Event(RetryRequest, d) =>
      goto(BootstrappingMeta)
    case Event(MetaBootstrapFinished, d) =>
      goto(nextState(stateName))
    case Event(MetaBootstrapTimeout, d) =>
      val mesg = "timed out waiting for bootstrap of gestalt-meta"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(SyncingMeta) {
    case Event(RetryRequest, d) =>
      goto(SyncingMeta)
    case Event(MetaSyncFinished, d) =>
      goto(nextState(stateName))
    case Event(MetaSyncTimeout, d) =>
      val mesg = "timed out waiting for sync of gestalt-meta"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(ProvisioningMetaProviders) {
    case Event(RetryRequest, d) =>
      goto(ProvisioningMetaProviders)
    case Event(MetaProvidersProvisioned, d) =>
      goto(nextState(stateName))
    case Event(MetaProviderTimeout, d) =>
      val mesg = "timed out provisioning providers in gestalt-meta"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(AllServicesLaunched)(FSM.NullFunction)
  when(Error)(FSM.NullFunction)

  when(ShuttingDown) {
    case Event(e, _) if e != StatusRequest =>
      log.info(s"ignoring event because of shutdown request: ${e.getClass.getName}")
      stay
  }

  whenUnhandled {
    case Event(ShutdownRequest,d) =>
      sender() ! ShutdownAcceptedResponse
      marClient.killApps.map {
        case true =>
          log.info("shutdown was successful")
        case false =>
          log.error("shutdown was not successful; manual cleanup may be necessary")
      }
      goto(ShuttingDown) using ServiceData.empty
    case Event(ErrorEvent(message,errorStage),d) =>
      goto(Error) using d.copy(
        error = Some(message),
        errorStage = errorStage
      )
    case Event(StatusRequest,d) =>
      stay replying StatusResponse(launcherStage = stateName match {
        case Error => d.errorStage.map(s => s"Error during ${s}").getOrElse("Error")
        case _ => stateName.toString
      }, error = d.error)
    case Event(APIKeyTimeout,d) =>
      stay
    case Event(MetaBootstrapTimeout,d) =>
      stay
    case Event(MetaSyncTimeout,d) =>
      stay
  }

  onTransition {
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

  initialize()
}
