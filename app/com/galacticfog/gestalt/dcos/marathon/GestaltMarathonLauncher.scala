package com.galacticfog.gestalt.dcos.marathon

import scala.language.postfixOps

import java.util.UUID
import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM}
import akka.event.LoggingAdapter
import com.galacticfog.gestalt.dcos.{GestaltTaskFactory, GlobalDBConfig, LauncherConfig}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import de.heikoseeberger.akkasse.ServerSentEvent
import play.api.libs.json.{JsObject, Json}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import play.api.libs.json._
import play.api.libs.json.Reads._
import LauncherConfig.Services._

sealed trait LauncherState

trait LaunchingState extends LauncherState {
  def targetService: FrameworkService
}

// in order...
case object Uninitialized             extends LauncherState
case object LaunchingDB               extends LaunchingState {val targetService = DATA}
case object LaunchingRabbit           extends LaunchingState {val targetService = RABBIT}
case object LaunchingSecurity         extends LaunchingState {val targetService = SECURITY}
case object RetrievingAPIKeys         extends LauncherState
case object LaunchingKong             extends LaunchingState {val targetService = KONG}
case object LaunchingApiGateway       extends LaunchingState {val targetService = API_GATEWAY}
case object LaunchingLaser            extends LaunchingState {val targetService = LASER}
case object LaunchingMeta             extends LaunchingState {val targetService = META}
case object BootstrappingMeta         extends LauncherState
case object SyncingMeta               extends LauncherState
case object ProvisioningMetaProviders extends LauncherState
case object ProvisioningMetaLicense   extends LauncherState
case object LaunchingApiProxy         extends LaunchingState {val targetService = API_PROXY}
case object LaunchingUI               extends LaunchingState {val targetService = UI}
case object LaunchingPolicy           extends LaunchingState {val targetService = POLICY}
case object AllServicesLaunched       extends LauncherState
// exceptional states
case object ShuttingDown              extends LauncherState
case object Error                     extends LauncherState

final case class ServiceData( statuses: Map[FrameworkService,ServiceInfo],
                              adminKey: Option[GestaltAPIKey],
                              error: Option[String],
                              errorStage: Option[String],
                              connected: Boolean ) {
  def getUrl(service: FrameworkService): Seq[String] = {
    statuses.get(service)
      .filter(_.hostname.isDefined)
      .map({case ServiceInfo(_,_,hostname,ports,_) => ports.map(p => hostname.get + ":" + p.toString)})
      .getOrElse(Seq.empty)
  }
}
case object ServiceData {
  def empty: ServiceData = ServiceData(Map.empty, None, None, None, false)
}

case object OpenConnectionToMarathonEventBus
case object StatusRequest
case object LaunchServicesRequest
case class ShutdownRequest(shutdownDB: Boolean)
case object ShutdownAcceptedResponse
case object RetryRequest
final case class KillRequest(service: FrameworkService)
final case class ErrorEvent(message: String, errorStage: Option[String])
final case class SecurityInitializationComplete(key: GestaltAPIKey)
final case class UpdateServiceInfo(info: ServiceInfo)
final case class ServiceDeployed(service: FrameworkService)
case object APIKeyTimeout
case object MetaBootstrapFinished
case object MetaBootstrapTimeout
case object MetaSyncFinished
case object MetaSyncTimeout
case object MetaProvidersProvisioned
case object MetaProviderTimeout
case object MetaLicensingComplete
case object MetaLicenseTimeout

final case class StatusResponse(launcherStage: String, error: Option[String], services: Seq[ServiceInfo], isConnectedToMarathon: Boolean)

case object StatusResponse {
  implicit val statusResponseWrites = Json.writes[StatusResponse]
}

object GestaltMarathonLauncher {
  val LAUNCH_ORDER: Seq[LauncherState] = Seq(
    LaunchingDB, LaunchingRabbit,
    LaunchingSecurity, RetrievingAPIKeys,
    LaunchingKong, LaunchingApiGateway,
    LaunchingLaser,
    LaunchingMeta, BootstrappingMeta, SyncingMeta, ProvisioningMetaProviders, ProvisioningMetaLicense,
    LaunchingPolicy,
    LaunchingApiProxy, LaunchingUI,
    AllServicesLaunched
  )
}

class GestaltMarathonLauncher @Inject()( launcherConfig: LauncherConfig,
                                         marClient: MarathonSSEClient,
                                         wsclient: WSClient,
                                         gtf: GestaltTaskFactory ) extends LoggingFSM[LauncherState,ServiceData] {

  import GestaltMarathonLauncher._
  import LauncherConfig.Services._

  private[this] def sendMessageToSelf[A](delay: FiniteDuration, message: A) = {
    this.context.system.scheduler.scheduleOnce(delay, self, message)
  }

  implicit val apiKeyReads = Json.format[GestaltAPIKey]

  val marathonBaseUrl = launcherConfig.marathon.baseUrl

  val TLD    = launcherConfig.marathon.tld
  val tldObj = TLD.map(tld => Json.obj("tld" -> tld))

  val marathonConfig = Json.obj(
    "marathon" -> tldObj.foldLeft(Json.obj(
    ))( _ ++ _ )
  )

  def provisionedDB: JsObject = Json.obj(
    "hostname" -> launcherConfig.vipHostname(DATA),
    "port" -> 5432,
    "username" -> launcherConfig.database.username,
    "password" -> launcherConfig.database.password,
    "prefix" -> "gestalt-"
  )

  def provisionedDBHostIP: JsObject = {
    val js = for {
      url <- stateData.getUrl(DATA).headOption
      parts = url.split(":")
      if parts.length == 2
      host = parts(0)
      port <- Try{parts(1).toInt}.toOption
    } yield Json.obj(
      "hostname" -> host,
      "port" -> port,
      "username" -> launcherConfig.database.username,
      "password" -> launcherConfig.database.password,
      "prefix" -> "gestalt-"
    )
    js getOrElse provisionedDB
  }

  def configuredDB: JsObject = Json.obj(
    "hostname" -> launcherConfig.database.hostname,
    "port" -> launcherConfig.database.port,
    "username" -> launcherConfig.database.username,
    "password" -> launcherConfig.database.password,
    "prefix" -> launcherConfig.database.prefix
  )

  val databaseConfig = if (launcherConfig.database.provision) Json.obj(
    "database" -> provisionedDB
  ) else Json.obj(
    "database" -> configuredDB
  )

  val globals = marathonConfig ++ databaseConfig

  val securityInitCredentials = JsObject(
    Seq("username" -> JsString(launcherConfig.security.username)) ++
      launcherConfig.security.password.map("password" -> JsString(_))
  )

  val securityProvidedApiKey = for {
    key <- launcherConfig.security.key
    secret <- launcherConfig.security.secret
  } yield GestaltAPIKey(apiKey = key, apiSecret = Some(secret), accountId = UUID.randomUUID(), disabled = false)

  private[this] def launchApp(service: FrameworkService, apiKey: Option[GestaltAPIKey] = None, secUrl: Option[String]): Unit = {
    val currentState = stateName.toString
    val allConfig = apiKey.map(apiKey => Json.obj(
      "security" -> JsObject(Seq(
        "apiKey" -> JsString(apiKey.apiKey),
        "apiSecret" -> JsString(apiKey.apiSecret.get)) ++ secUrl.map(
        "realm" -> JsString(_)
      ))
    )).fold(globals)(_ ++ globals)
    val payload = gtf.getMarathonPayload(service, allConfig)
    log.debug("'{}' launch payload:\n{}", service.name, Json.prettyPrint(Json.toJson(payload)))
    val fLaunch = marClient.launchApp(payload) map {
      r =>
        log.info("'{}' launch response: {}", service.name, r.toString)
        self ! ServiceDeployed(service)
    }
    // launch failed, so we'll never get a task update
    fLaunch.onFailure {
      case e: Throwable =>
        log.warning("error launching {}: {}",service.name,e.getMessage)
        self ! ErrorEvent(e.getMessage, errorStage = Some(currentState))
    }
  }

  private[this] def nextState(state: LauncherState): LauncherState = {
    val cur = LAUNCH_ORDER.indexOf(state)
    if (LAUNCH_ORDER.isDefinedAt(cur+1)) LAUNCH_ORDER(cur+1) else Error
  }

  private[this] def standardWhen(state: LaunchingState) = when(state) {
    case Event(e @ UpdateServiceInfo(status), d) if state.targetService == status.service =>
      val svcName = state.targetService
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
      if ( ! d.connected ) {
        sendMessageToSelf(0 seconds, OpenConnectionToMarathonEventBus)
      }
      if (launcherConfig.database.provision) {
        goto(LAUNCH_ORDER.head)
      } else {
        goto(nextState(LaunchingDB))
      }
  }

  when(ShuttingDown) {
    case Event(LaunchServicesRequest,d) =>
      if (launcherConfig.database.provision) {
        goto(LAUNCH_ORDER.head) using d.copy(
          error = None,
          errorStage = None
        )
      } else {
        goto(nextState(LaunchingDB)) using d.copy(
          error = None,
          errorStage = None
        )
      }
  }

  LAUNCH_ORDER.collect({case s: LaunchingState => s}).foreach(standardWhen)

  // service launch stages
  onTransition {
    case (_, stage: LaunchingState) => launchApp(stage.targetService, nextStateData.adminKey, nextStateData.getUrl(SECURITY).headOption)
  }

  // post-launch stages
  onTransition {
    case _ -> RetrievingAPIKeys =>
      nextStateData.getUrl(SECURITY) match {
        case Seq() => self ! ErrorEvent("while initializing security, missing security URL after launching security", Some(RetrievingAPIKeys.toString))
        case Seq(secUrl) =>
          val initUrl = s"http://${secUrl}/init"
          log.info(s"initializing security at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).post(securityInitCredentials) flatMap { resp =>
            log.info("security.init response: {}",resp.toString)
            resp.status match {
              case 200 =>
                Try{resp.json.as[Seq[GestaltAPIKey]].head} match {
                  case Success(key) =>
                    Future.successful(SecurityInitializationComplete(key))
                  case Failure(e) =>
                    Future.failed(new RuntimeException("while initializing security, error extracting API key form security initialization response"))
                }
              case 400 =>
                log.warning("400 from security init, likely that security service already initialized, cannot extract keys to configure downstream services")
                securityProvidedApiKey match {
                  case Some(key) =>
                    log.info("continuing with API keys from configuration")
                    Future.successful(SecurityInitializationComplete(securityProvidedApiKey.get))
                  case None =>
                    log.warning("attempting to clear init flag from security database")
                    val databaseConfig = if (launcherConfig.database.provision) Json.obj(
                      "database" -> provisionedDBHostIP
                    ) else Json.obj(
                      "database" -> configuredDB
                    )
                    SecurityInitReset(databaseConfig).clearInit()(log)
                    Future.failed(new RuntimeException("failed to init security; attempted to clear init flag and will try again"))
                }
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
      (nextStateData.getUrl(META), nextStateData.adminKey) match {
        case (Seq(),_) => self ! ErrorEvent("while bootstrapping meta, missing meta URL after launching meta", Some(BootstrappingMeta.toString))
        case (_,None) => self ! ErrorEvent("while bootstrapping meta, missing admin API key after initializing security", Some(BootstrappingMeta.toString))
        case (Seq(metaUrl),Some(apiKey)) =>
          val initUrl = s"http://${metaUrl}/bootstrap"
          val rootUrl = s"http://${metaUrl}/root"
          val bootstrap = for {
            check <- wsclient.url(rootUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).get()
            done <- if (check.status == 500) {
              log.info("attempting to bootstrap meta")
              wsclient.url(initUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey, apiKey.apiSecret.get, WSAuthScheme.BASIC).post("") flatMap { resp =>
                log.info("meta.bootstrap response: {}", resp.toString)
                resp.status match {
                  case 204 =>
                    Future.successful(MetaBootstrapFinished)
                  case not204 =>
                    val mesg = Try {
                      (resp.json \ "message").as[String]
                    }.getOrElse(resp.body)
                    Future.failed(new RuntimeException(mesg))
                }
              }
            } else {
              log.info("meta appears to already have been bootstrapped; will proceed with next stage")
              Future.successful(MetaBootstrapFinished)
            }
          } yield done
          bootstrap.onComplete {
            case Success(msg) => self ! msg
            case Failure(ex) =>
              log.warning("error bootstrapping meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
    case _ -> SyncingMeta =>
      (nextStateData.getUrl(META), nextStateData.adminKey) match {
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
      (nextStateData.getUrl(META), nextStateData.getUrl(KONG), nextStateData.adminKey) match {
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
            case Some(tld) => s"https://kong.${tld}"
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
               |      "url": "${gtf.vipDestination(KONG_SERVICE)}",
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
              // TODO: this potentially has the unfortunate effect of creating the providers multiple times; check before adding
              sendMessageToSelf(5.seconds, RetryRequest)
          }
        case (Seq(),_,_)  => self ! ErrorEvent("while provisioning providers, missing meta URL after launching meta", Some(SyncingMeta.toString))
        case (_,Seq(_),_) => self ! ErrorEvent("while provisioning providers, missing kong URL after launching kong", Some(SyncingMeta.toString))
        case (_,_,None)   => self ! ErrorEvent("while provisioning providers, missing admin API key after initializing security", Some(SyncingMeta.toString))
      }
    case _ -> ProvisioningMetaLicense =>
      (nextStateData.getUrl(META), nextStateData.adminKey) match {
        case (Seq(),_) => self ! ErrorEvent("while provisioning meta license, missing meta URL after launching meta", Some(BootstrappingMeta.toString))
        case (_,None) => self ! ErrorEvent("while provisioning meta license, missing admin API key after initializing security", Some(BootstrappingMeta.toString))
        case (Seq(metaUrl),Some(apiKey)) =>
          val licenseBody = Json.obj(
            "name" -> "Default-License-1",
            "description" -> "Default GF license",
            "properties" -> Json.obj(
              "data" -> "ABwwGgQU0yrowwdZqokCoV+gsama8FPom7gCAgQANZ0VCWktNu7ACEokBT2hTHLu+ZDhGWZCeANF1klgMvRSoQmerkWTED1hCrTw3H0A0UC/TyU/9B0kjs9aT11H5uTQticJZnJ3GCn4/DLawLJ+JDwktF0pA9ATTVieJcqW1VDE3vgpgU6HllQOfBGmsmASPEOtYBte+gbKn5msKpUvbooLW5xyNKKe9U7vmmtvI2Th3wgb354k2imi8LJaVxywKd9BRe78mWhsKa9d57V+n9NAlQfN24wcyxzc23VuMK/v3fFl6YT3ro9ddXlIoRMl7mJ3nWN2HtYlfHTULsAsbIwHUsY44WZI926RDunstC03dm+mfT9jDTIciJ38jX0lGvbAY1X3ovl9v3EoroIjXwz4JKg5FbEH2vdoFwdy1UznCxSq10537hwmmnEfC16JUFySfe0o"
            )
          )
          val licenseUrl = s"http://${metaUrl}/root/licenses"
          val licenseAttempt = wsclient.url(licenseUrl).withRequestTimeout(30.seconds).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC).post(licenseBody) flatMap { resp =>
            log.info("meta.license response: {}",resp.toString)
            resp.status match {
              case 201 =>
                Future.successful(MetaLicensingComplete)
              case not200 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          licenseAttempt.onComplete {
            case Success(msg) => self ! msg
            case Failure(ex) =>
              log.warning("error licensing meta service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
  }

  // only setup these timeouts on the first transition
  onTransition {
    case prev -> RetrievingAPIKeys         if prev != RetrievingAPIKeys         => sendMessageToSelf(5.minutes, APIKeyTimeout)
    case prev -> BootstrappingMeta         if prev != BootstrappingMeta         => sendMessageToSelf(5.minutes, MetaBootstrapTimeout)
    case prev -> SyncingMeta               if prev != SyncingMeta               => sendMessageToSelf(5.minutes, MetaSyncTimeout)
    case prev -> ProvisioningMetaProviders if prev != ProvisioningMetaProviders => sendMessageToSelf(5.minutes, MetaProviderTimeout)
    case prev -> ProvisioningMetaLicense   if prev != ProvisioningMetaLicense   => sendMessageToSelf(5.minutes, MetaLicenseTimeout)
  }

  when(RetrievingAPIKeys) {
    case Event(RetryRequest, d) =>
      goto(RetrievingAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
      log.info("received apiKey:\n{}",Json.prettyPrint(Json.toJson(apiKey)))
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

  when(ProvisioningMetaLicense) {
    case Event(RetryRequest, d) =>
      goto(ProvisioningMetaLicense)
    case Event(MetaLicensingComplete, d) =>
      goto(nextState(stateName))
    case Event(MetaLicenseTimeout, d) =>
      val mesg = "timed out licensing gestalt-meta"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(AllServicesLaunched)(FSM.NullFunction)
  when(Error)(FSM.NullFunction)

  object FrameworkServiceFromAppId {
    private [this] val appIdWithGroup = s"/${launcherConfig.marathon.appGroup}/(.*)".r
    def unapply(appId: String): Option[FrameworkService] = appIdWithGroup.unapplySeq(appId) flatMap(_.headOption) flatMap(LauncherConfig.Services.fromName)
  }

  def requestUpdateAndStay(service: FrameworkService) = {
    marClient.getServiceStatus(service).onComplete {
      case Success(status) =>
        self ! UpdateServiceInfo(status)
      case Failure(ex) =>
        log.warning("error retrieving app status from Marathon: {}",ex.getMessage)
    }
    stay()
  }

  whenUnhandled {
    case Event(OpenConnectionToMarathonEventBus, d) =>
      if (d.connected) {
        log.info("ignoring request OpenConnectionToMarathonEventBus, I think I'm already connected")
      } else {
        log.info("attempting to connect to Marathon event bus")
        marClient.connectToBus(self)
      }
      stay()
    case Event(MarathonSSEClient.Connected, d) =>
      log.info("successfully connected to Marathon event bus")
      stay() using d.copy(
        connected = true
      )
    case Event(MarathonSSEClient.Disconnected, d) =>
      log.error("connection to Marathon event bus was closed, will attempt to reconnect")
      sendMessageToSelf(30 seconds, OpenConnectionToMarathonEventBus)
      stay() using d.copy(
        connected = false
      )
    case Event(MarathonSSEClient.Failed(t), d) =>
      log.error("error connecting to Marathon event bus, will attempt to reconnect", t)
      sendMessageToSelf(30 seconds, OpenConnectionToMarathonEventBus)
      stay() using d.copy(
        connected = false
      )
    case Event(sse @ ServerSentEvent(Some(data), Some(eventType), _, _), d) =>
      val mesg = eventType match {
        case "app_terminated_event"        => MarathonSSEClient.parseEvent[MarathonAppTerminatedEvent](sse)
        case "health_status_changed_event" => MarathonSSEClient.parseEvent[MarathonHealthStatusChange](sse)
        case "status_update_event"         => MarathonSSEClient.parseEvent[MarathonStatusUpdateEvent](sse)
        case "deployment_failed"           => MarathonSSEClient.parseEvent[MarathonDeploymentFailure](sse)
        case _ => {
          log.debug(s"ignoring event ${eventType}")
          None
        }
      }
      mesg.foreach(sse => self ! sse)
      stay()
    case Event(sse @ ServerSentEvent(maybeData, maybeEventType, _, _), d) =>
      log.debug("received server-sent-event heartbeat from marathon event bus")
      stay
    case Event(UpdateServiceInfo(status), d) =>
      stay() using d.copy(
        statuses = d.statuses + (status.service -> status)
      )
    case Event(ServiceDeployed(service), d) =>
      requestUpdateAndStay(service) using d.copy(
        statuses = d.statuses + (service -> ServiceInfo(
          service = service,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = LAUNCHING
        ))
      )
    case Event(e @ MarathonAppTerminatedEvent(FrameworkServiceFromAppId(service),_,_), d) =>
      log.info(s"received app terminated event from service ${service.name}")
      stay() using d.copy(
        statuses = d.statuses + (service -> ServiceInfo(
          service = service,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = NOT_FOUND
        ))
      )
    case Event(e @ MarathonAppTerminatedEvent(nonFrameworkAppId,_,_), d) =>
      log.debug(s"received app terminated event from non-framework service ${nonFrameworkAppId}")
      stay()
    case Event(e @ MarathonDeploymentFailure(_, _, deploymentId), d) =>
      log.info(s"ignoring MarathonDeploymentFailure(${deploymentId})")
      stay()
//      goto(Error) using d.copy(
//        error = Some(s"Deployment ${deploymentId} failed for service (unknown)"), // TODO: do something appropriate here, once we've started storing deployment IDs
//        errorStage = Some(stateName.toString)
//      )
    case Event(e @ MarathonHealthStatusChange(_, _, FrameworkServiceFromAppId(service), taskId, _, alive), d) =>
      log.info(s"received MarathonHealthStatusChange(${taskId}.alive == ${alive}) for task belonging to ${service.name}")
      val updatedStatus = d.statuses.get(service).map(info =>
        service -> info.copy(
          status = if (alive) HEALTHY else UNHEALTHY
        )
      )
      updatedStatus.headOption.foreach{
        case (svcName,info) => log.info(s"marking ${svcName} as ${info.status}")
      }
      stay() using d.copy(
        statuses = d.statuses ++ updatedStatus
      )
    case Event(e @ MarathonStatusUpdateEvent(_, _, taskStatus, _, FrameworkServiceFromAppId(service), _, _, _, _, _, _) , d) =>
      log.info(s"received StatusUpdateEvent(${taskStatus}) for task belonging to ${service.name}")
      requestUpdateAndStay(service)
    case Event(e @ MarathonStatusUpdateEvent(_, _, taskStatus, _, nonFrameworkAppId, _, _, _, _, _, _) , d) =>
      log.debug(s"ignoring StatusUpdateEvent(${taskStatus}) for task from non-framework app ${nonFrameworkAppId}")
      stay()
    case Event(LaunchServicesRequest,d) =>
      log.info("ignoring LauncherServicesRequest in stage " + stateName)
      stay()
    case Event(ShutdownRequest(shutdownDB),d) =>
      sender() ! ShutdownAcceptedResponse
      val deleteApps = LAUNCH_ORDER
        .collect({case s: LaunchingState => s.targetService})
        .filter { svc => (shutdownDB || svc != DATA) }
        .reverse
      deleteApps.foreach {
        service => marClient.killApp(service)
      }
      goto(ShuttingDown) using d.copy(
        error = None,
        errorStage = None
      )
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
        case Error => d.errorStage.map("Error during ".+).getOrElse("Error")
        case _ => stateName.toString
      }
      val services = launcherConfig.provisionedServices.map(service =>
        d.statuses.get(service) getOrElse ServiceInfo(
          service = service,
          vhosts = Seq.empty,
          hostname = None,
          ports = Seq.empty,
          status = NOT_FOUND
        )
      )
      stay replying StatusResponse(
        launcherStage = stage,
        error = d.error,
        services = services,
        isConnectedToMarathon = d.connected
      )
    case Event(APIKeyTimeout,d) =>
      stay
    case Event(MetaBootstrapTimeout,d) =>
      stay
    case Event(MetaSyncTimeout,d) =>
      stay
    case Event(e, d) =>
      log.warning("unhandled event of type " + e.getClass.toString)
      stay
  }

  onTransition {
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

  initialize()
}

case class SecurityInitReset(dbConfig: JsObject) {
  import scalikejdbc._
  def clearInit()(implicit log: LoggingAdapter): Unit = {
    val db = GlobalDBConfig(dbConfig)

    val driver = "org.postgresql.Driver"
    val url = "jdbc:postgresql://%s:%d/%s".format(db.hostname, db.port, db.prefix + "security")
    log.info("initializing connection pool against " + url)

    Class.forName(driver)

    val settings = ConnectionPoolSettings(
      connectionTimeoutMillis = 5000
    )

    ConnectionPool.singleton(url, db.username, db.password, settings)
    println("ConnectionPool.isInitialized: " + ConnectionPool.isInitialized())

    implicit val session = AutoSession
    Try {
      sql"update initialization_settings set initialized = false where id = 0".execute.apply()
      ConnectionPool.closeAll()
    } recover {
      case e: Throwable =>
        log.warning(s"error clearing init flag on ${db.prefix}security database: {}", e.getMessage)
        false
    }

    ConnectionPool.closeAll()
  }
}
