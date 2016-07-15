package com.galacticfog.gestalt.dcos.marathon

import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM}
import com.galacticfog.gestalt.dcos.{marathon, GestaltTaskFactory}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import de.heikoseeberger.akkasse.ServerSentEvent
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Try}
import com.galacticfog.gestalt.dcos.marathon._
import akka.pattern.ask
import MarathonSSEClient.parseEvent
import com.galacticfog.gestalt.dcos.marathon._

sealed trait LauncherState {
  def targetService: Option[String] = None
}
// in order...

case object Uninitialized             extends LauncherState
case object LaunchingDB               extends LauncherState {override def targetService = Some("data")}
case object LaunchingRabbit           extends LauncherState {override def targetService = Some("rabbit")}
case object LaunchingSecurity         extends LauncherState {override def targetService = Some("security")}
case object RetrievingAPIKeys         extends LauncherState
case object LaunchingKong             extends LauncherState {override def targetService = Some("kong")}
case object LaunchingApiGateway       extends LauncherState {override def targetService = Some("api-gateway")}
case object LaunchingLambda           extends LauncherState {override def targetService = Some("lambda")}
case object LaunchingMeta             extends LauncherState {override def targetService = Some("meta")}
case object BootstrappingMeta         extends LauncherState
case object SyncingMeta               extends LauncherState
case object ProvisioningMetaProviders extends LauncherState
case object LaunchingApiProxy         extends LauncherState {override def targetService = Some("api-proxy")}
case object LaunchingUI               extends LauncherState {override def targetService = Some("ui")}
case object LaunchingPolicy           extends LauncherState {override def targetService = Some("policy")}
case object AllServicesLaunched       extends LauncherState
// failure
case object ShuttingDown              extends LauncherState
case object Error                     extends LauncherState

final case class ServiceData(statuses: Map[String,ServiceInfo],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String],
                             errorStage: Option[String])
case object ServiceData {
  def empty: ServiceData = ServiceData(Map.empty,None,None,None)
}

case object StatusRequest
case object LaunchServicesRequest
case class ShutdownRequest(shutdownDB: Boolean)
case object ShutdownAcceptedResponse
case object RetryRequest
final case class KillRequest(serviceName: String)
final case class ErrorEvent(message: String, errorStage: Option[String])
final case class SecurityInitializationComplete(key: Option[GestaltAPIKey])
final case class UpdateServiceInfo(info: ServiceInfo)
final case class ServiceDeployed(serviceName: String)
case object APIKeyTimeout
case object MetaBootstrapFinished
case object MetaBootstrapTimeout
case object MetaSyncFinished
case object MetaSyncTimeout
case object MetaProvidersProvisioned
case object MetaProviderTimeout

final case class StatusResponse(launcherStage: String, error: Option[String], services: Seq[ServiceInfo])

case object StatusResponse {
  implicit val statusResponseWrites = Json.writes[StatusResponse]
}

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

  def getUrl(state: LauncherState): Seq[String] = {
    state.targetService
      .flatMap(nextStateData.statuses.get)
      .filter(_.hostname.isDefined)
      .map({case ServiceInfo(_,_,hostname,ports,_) => ports.map(p => hostname.get + ":" + p.toString)})
      .getOrElse(Seq.empty)
  }

  def sendMessageToSelf[A](delay: FiniteDuration, message: A) = {
    this.context.system.scheduler.scheduleOnce(delay, self, message)
  }

  implicit val apiKeyReads = Json.format[GestaltAPIKey]

  val marathonBaseUrl = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"


  val TLD    = config.getString("marathon.tld").map(tld => Json.obj("tld" -> tld))
  val tldObj = TLD.map(tld => Json.obj("tld" -> tld))

  val VIP = config.getString("service.vip") getOrElse "10.10.10.10"

  // setup a-priori/static globals

  val marathonConfig = Json.obj(
    "marathon" -> tldObj.foldLeft(Json.obj(
    ))( _ ++ _ )
  )

  def provisionedDB: JsObject = Json.obj(
    "hostname" -> VIP,
    "port" -> 5432,
    "username" -> getString("database.username", "gestaltdev"),
    "password" -> getString("database.password", "letmein"),
    "prefix" -> "gestalt-"
  )

  def configuredDB: JsObject = Json.obj(
    "hostname" -> getString("database.hostname", "data.gestalt.marathon.mesos"),
    "port" -> getInt("database.port", 5432),
    "username" -> getString("database.username", "gestaltdev"),
    "password" -> getString("database.password", "letmein"),
    "prefix" -> getString("database.prefix", "gestalt-")
  )

  val databaseConfig = if (gtf.provisionDB) Json.obj(
    "database" -> provisionedDB
  ) else Json.obj(
    "database" -> configuredDB
  )

  val globals = marathonConfig ++ databaseConfig

  val securityCredentials = Json.obj(
    "username" -> getString("security.username","gestalt-admin")
  ) ++ config.getString("security.password").map(p => Json.obj("password" -> p)).getOrElse(Json.obj())

  private def launchApp(serviceName: String, apiKey: Option[GestaltAPIKey] = None): Unit = {
    val currentState = nextStateData.toString
    val allConfig = apiKey.map(apiKey => Json.obj(
      "security" -> Json.obj(
        "apiKey" -> apiKey.apiKey,
        "apiSecret" -> apiKey.apiSecret.get
      )
    )).fold(globals)(sec => sec ++ globals)
    val payload = gtf.getMarathonPayload(serviceName, allConfig)
    log.debug("'{}' launch payload:\n{}", serviceName, Json.prettyPrint(Json.toJson(payload)))
    val fLaunch = marClient.launchApp(payload) map {
      r =>
        log.debug("'{}' launch response: {}", serviceName, r.toString)
        self ! ServiceDeployed(serviceName)
    }
    // launch failed, so we'll never get a task update
    fLaunch.onFailure {
      case e: Throwable =>
        log.warning("error launching {}: {}",serviceName,e.getMessage)
        self ! ErrorEvent(e.getMessage,errorStage = Some(currentState))
    }
  }

  private def nextState(state: LauncherState): LauncherState = {
    val cur = LAUNCH_ORDER.indexOf(state)
    if (LAUNCH_ORDER.isDefinedAt(cur+1)) LAUNCH_ORDER(cur+1) else Error
  }

  private def prevState(state: LauncherState): LauncherState = {
    val cur = LAUNCH_ORDER.indexOf(state)
    if (LAUNCH_ORDER.isDefinedAt(cur-1)) LAUNCH_ORDER(cur-1) else Error
  }

  private def standardWhen(state: LauncherState) = when(state) {
    case Event(e @  UpdateServiceInfo(status), d) if status.serviceName == state.targetService.get =>
      val svcName = state.targetService.get
      log.info(s"while launching, ${svcName} updated to ${status.status}")
      val newData = d.copy(
        statuses = d.statuses + (svcName -> status)
      )
      if (status.status == RUNNING || status.status == HEALTHY)
        goto(nextState(state)) using newData
      else
        stay() using newData
  }

  startWith(Uninitialized, ServiceData.empty)

  when(Uninitialized) {
    case Event(LaunchServicesRequest,d) =>
      if (gtf.provisionDB) {
        goto(LAUNCH_ORDER.head)
      } else {
        goto(nextState(LaunchingDB))
      }
  }

  when(ShuttingDown) {
    case Event(LaunchServicesRequest,d) =>
      if (gtf.provisionDB) {
        goto(LAUNCH_ORDER.head)
      } else {
        goto(nextState(LaunchingDB))
      }
  }

  LAUNCH_ORDER.filter(_.targetService.isDefined).foreach(standardWhen)

  // service launch stages
  onTransition {
    case _ -> stage if stage.targetService.isDefined => launchApp(stage.targetService.get, nextStateData.adminKey)
  }

  // post-launch stages
  onTransition {
    case _ -> RetrievingAPIKeys =>
      getUrl(LaunchingSecurity) match {
        case Seq() => self ! ErrorEvent("while initializing security, missing security URL after launching security", Some(RetrievingAPIKeys.toString))
        case Seq(secUrl) =>
          val initUrl = s"http://${secUrl}/init"
          log.info(s"initializing security at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).post(securityCredentials) flatMap { resp =>
            log.info("security.init response: {}",resp.toString)
            resp.status match {
              case 200 =>
                Try{resp.json.as[Seq[GestaltAPIKey]].head} match {
                  case Success(key) =>
                    Future.successful(SecurityInitializationComplete(Some(key)))
                  case Failure(e) =>
                    Future.failed(new RuntimeException("while initializing security, error extracting API key form security initialization response"))
                }
              case 400 =>
                log.warning("400 from security init, likely that security service already initialized, cannot extract keys to configure downstream services")
                Future.successful(SecurityInitializationComplete(None))
              case _ =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(msg) =>
              self ! msg
            case Failure(ex) =>
              log.warning("error initializing security service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> BootstrappingMeta =>
      (getUrl(LaunchingMeta),nextStateData.adminKey) match {
        case (Seq(),_) => self ! ErrorEvent("while bootstrapping meta, missing meta URL after launching meta", Some(BootstrappingMeta.toString))
        case (_,None) => self ! ErrorEvent("while bootstrapping meta, missing admin API key after initializing security", Some(BootstrappingMeta.toString))
        case (Seq(metaUrl),Some(apiKey)) =>
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
            case Success(msg) => self ! msg
            case Failure(ex) =>
              log.warning("error bootstrapping meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> SyncingMeta =>
      (getUrl(LaunchingMeta),nextStateData.adminKey) match {
        case (Seq(),_) => self ! ErrorEvent("while syncing meta, missing meta URL after launching meta", Some(SyncingMeta.toString))
        case (_,None) => self ! ErrorEvent("while syncing meta, missing admin API key after initializing security", Some(SyncingMeta.toString))
        case (Seq(metaUrl),Some(apiKey)) =>
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
            case Success(msg) => self ! msg
            case Failure(ex) =>
              log.warning("error syncing meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> ProvisioningMetaProviders =>
      (getUrl(LaunchingMeta),getUrl(LaunchingKong),nextStateData.adminKey) match {
        case (Seq(metaUrl),Seq(kongGatewayUrl,kongServiceUrl),Some(apiKey)) =>
          val initUrl = s"http://${metaUrl}/root/providers"
          val marathonProviderJson = Json.parse(
            s"""
              |{
              |  "description": "",
              |  "resource_type": "Gestalt::Configuration::Provider::Marathon",
              |  "properties": {
              |    "environments": [],
              |    "config": {
              |      "auth": { "scheme": "Basic", "username": "username", "password": "password" },
              |      "url": "${marathonBaseUrl}",
              |      "networks": [
              |        { "name": "HOST" },
              |        { "name": "BRIDGE" }
              |      ],
              |      "extra": {}
              |    },
              |    "locations": [
              |      { "name": "dcos", "enabled": true }
              |    ]
              |  },
              |  "name": "base-marathon"
              |}
            """.stripMargin)
          val kongExternalAccess = TLD match {
            case Some(tld) => s"https://gateway-kong.${tld}"
            case None => s"http://${kongGatewayUrl}"
          }
          val kongProviderJson = Json.parse(
            s"""
               |{
               |  "description": "",
               |  "resource_type": "Gestalt::Configuration::Provider::ApiGateway",
               |  "properties": {
               |    "environments": [],
               |    "config": {
               |      "auth": { "scheme": "Basic", "username": "username", "password": "password" },
               |      "url": "${kongServiceUrl}",
               |      "extra": "${kongExternalAccess}"
               |    },
               |    "locations": [
               |      { "name": "dcos", "enabled": true }
               |    ]
               |  },
               |  "name": "base-kong"
               |}
            """.stripMargin)

          log.info(s"provisioning providers in meta at {}",initUrl)
          val marathonAttempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post(marathonProviderJson) flatMap { resp =>
            log.info("meta.provision(marathonProvider) response: {}",resp.toString)
            resp.status match {
              case 201 =>
                Future.successful(MetaProvidersProvisioned)
              case not201 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException("Error provisioning marathon provider: " + mesg))
            }
          }
          val kongAttempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post(kongProviderJson) flatMap { resp =>
            log.info("meta.provision(kongProvider) response: {}",resp.toString)
            resp.status match {
              case 201 =>
                Future.successful(MetaProvidersProvisioned)
              case not201 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException("Error provisioning kong provider: " + mesg))
            }
          }
          val fProviders = Future.sequence(Seq(marathonAttempt,kongAttempt))
          fProviders.onComplete {
            case Success(msg) => self ! msg.head // both are MetaProvidersProvisioned
            case Failure(ex) =>
              log.warning("error provisioning providers in meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
        case (Seq(),_,_)  => self ! ErrorEvent("while provisioning providers, missing meta URL after launching meta", Some(SyncingMeta.toString))
        case (_,Seq(_),_) => self ! ErrorEvent("while provisioning providers, missing kong URL after launching kong", Some(SyncingMeta.toString))
        case (_,_,None)   => self ! ErrorEvent("while provisioning providers, missing admin API key after initializing security", Some(SyncingMeta.toString))
      }
  }

  // only setup these timeouts on the first transition
  onTransition {
    case prev -> RetrievingAPIKeys         if prev != RetrievingAPIKeys         => sendMessageToSelf(5.minutes, APIKeyTimeout)
    case prev -> BootstrappingMeta         if prev != BootstrappingMeta         => sendMessageToSelf(5.minutes, MetaBootstrapTimeout)
    case prev -> SyncingMeta               if prev != SyncingMeta               => sendMessageToSelf(5.minutes, MetaSyncTimeout)
    case prev -> ProvisioningMetaProviders if prev != ProvisioningMetaProviders => sendMessageToSelf(5.minutes, MetaProviderTimeout)
  }

  when(RetrievingAPIKeys) {
    case Event(RetryRequest, d) =>
      goto(RetrievingAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
      log.debug("received apiKey:\n{}",Json.prettyPrint(Json.toJson(apiKey)))
      goto(nextState(stateName)) using d.copy(
        adminKey = apiKey
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

  val appIdWithGroup = s"/${gtf.appGroup}/(.*)".r

  def requestUpdateAndStay(svcName: String) = {
    marClient.getServiceStatus(svcName).onComplete {
      case Success(status) =>
        self ! UpdateServiceInfo(status)
      case Failure(ex) =>
        log.warning("error retrieving app status from Marathon: {}",ex.getMessage)
    }
    stay()
  }

  whenUnhandled {
    case Event(sse @ ServerSentEvent(data, Some(eventType), _, _), d) =>
      val mesg = eventType match {
        case "app_terminated_event"        => parseEvent[MarathonAppTerminatedEvent](sse)
        case "health_status_changed_event" => parseEvent[MarathonHealthStatusChange](sse)
        case "status_update_event"         => parseEvent[MarathonStatusUpdateEvent](sse)
        case "deployment_success"          => parseEvent[MarathonDeploymentSuccess](sse)
        case "deployment_failure"          => parseEvent[MarathonDeploymentFailure](sse)
        case _ => None
      }
      mesg.foreach(sse => self ! sse)
      stay()
    case Event(UpdateServiceInfo(status), d) =>
      stay() using d.copy(
        statuses = d.statuses + (status.serviceName -> status)
      )
    case Event(ServiceDeployed(serviceName), d) =>
      requestUpdateAndStay(serviceName) using d.copy(
        statuses = d.statuses + (serviceName -> ServiceInfo(
          serviceName = serviceName,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = LAUNCHING
        ))
      )
    case Event(e @ MarathonAppTerminatedEvent(appIdWithGroup(svcName),_,_), d) =>
      log.info(s"received app terminated event for ${svcName}")
      stay() using d.copy(
        statuses = d.statuses + (svcName -> ServiceInfo(
          serviceName = svcName,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = NOT_FOUND
        ))
      )
    case Event(e @ MarathonDeploymentFailure(_, _, appIdWithGroup(svcName)), d) =>
      goto(Error) using d.copy(
        error = Some(s"Deployment failed for service ${svcName}"),
        errorStage = Some(stateName.toString)
      )
    case Event(e @ MarathonHealthStatusChange(_, _, appIdWithGroup(svcName), taskId, _, alive), d) =>
      log.info(s"received MarathonHealthStatusChange(${taskId}.alive == ${alive}) for task belonging to ${svcName}")
      requestUpdateAndStay(svcName)
    case Event(e @ MarathonDeploymentSuccess(_, _, appIdWithGroup(svcName)) , d) =>
      log.info(s"received MarathonDeploymentSuccess for task belonging to ${svcName}")
      requestUpdateAndStay(svcName)
    case Event(e @ MarathonStatusUpdateEvent(_, _, taskStatus, _, appIdWithGroup(svcName), _, _, _, _, _, _) , d) =>
      log.info(s"received StatusUpdateEvent(${taskStatus}) for task belonging to ${svcName}")
      requestUpdateAndStay(svcName)
    case Event(LaunchServicesRequest,d) =>
      log.info("ignoring LauncherServicesRequest in stage " + stateName)
      stay()
    case Event(ShutdownRequest(shutdownDB),d) =>
      sender() ! ShutdownAcceptedResponse
      val deleteApps = LAUNCH_ORDER
        .flatMap {_.targetService}
        .filter { svc => (shutdownDB || svc != LaunchingDB.targetService.get)}
        .reverse
      // shut down slowly
//      deleteApps.foldLeft(1.seconds){
//        (delay,serviceName) =>
//          sendMessageToSelf(delay, KillRequest(serviceName))
//          delay + 2.seconds
//      }
      deleteApps.foreach {
        svcName => marClient.killApp(svcName)
      }
      goto(ShuttingDown)
    case Event(KillRequest(serviceName), d) =>
      log.info(s"received request to kill '${serviceName}'")
      marClient.killApp(serviceName) onComplete {
        case Success(killed) =>
          log.warning(s"marathon.kill(${serviceName}) returned ${killed}")
        case Failure(ex) =>
          log.warning(s"failure killing '${serviceName}': ${ex.getMessage}")
      }
      stay()
    case Event(ErrorEvent(message,errorStage),d) =>
      goto(Error) using d.copy(
        error = Some(message),
        errorStage = errorStage
      )
    case Event(StatusRequest,d) =>
      val stage = stateName match {
        case Error => d.errorStage.map(s => s"Error during ${s}").getOrElse("Error")
        case _ => stateName.toString
      }
      val services = gtf.allServices.map(svcName =>
        d.statuses.get(svcName) getOrElse ServiceInfo(
          serviceName = svcName,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = NOT_FOUND
        )
      )
      stay replying StatusResponse(
        launcherStage = stage,
        error = d.error,
        services = services
      )
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
