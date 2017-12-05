package com.galacticfog.gestalt.dcos.launcher

import java.io.{PrintWriter, StringWriter}
import java.util.UUID
import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM, Status}
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.ServiceStatus._
import com.galacticfog.gestalt.dcos._
import com.galacticfog.gestalt.dcos.marathon.{MarathonAppTerminatedEvent, MarathonHealthStatusChange, MarathonSSEClient, MarathonStatusUpdateEvent}
import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import de.heikoseeberger.akkasse.ServerSentEvent
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Reads._
import play.api.libs.json.{JsObject, Json, _}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSRequest, WSResponse}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object LauncherFSM {

  object Messages {

    // public messages
    case object StatusRequest

    case object LaunchServicesRequest

    case class ShutdownRequest(shutdownDB: Boolean)

    case object ShutdownAcceptedResponse

    final case class StatusResponse(launcherStage: String, error: Option[String], services: Seq[ServiceInfo], isConnectedToMarathon: Boolean)

    case object StatusResponse {
      implicit val statusResponseWrites: OWrites[StatusResponse] = Json.writes[StatusResponse]
    }

    // private messages: internal only
    private[launcher] case class RetryRequest(state: LauncherState)

    private[launcher] case object OpenConnectionToMarathonEventBus

    private[launcher] final case class ErrorEvent(message: String, errorStage: Option[String])

    private[launcher] final case class SecurityInitializationComplete(key: GestaltAPIKey)

    private[launcher] case object AdvanceStage

    private[launcher] case object Sync

    private[launcher] final case class UpdateServiceInfo(info: ServiceInfo)

    private[launcher] final case class UpdateAllServiceInfo(all: Seq[ServiceInfo])

    private[launcher] final case class ServiceDeployed(service: FrameworkService)

    private[launcher] final case class ServiceDeleting(service: FrameworkService)

    private[launcher] sealed trait TimeoutEvent

    private[launcher] case object APIKeyTimeout extends TimeoutEvent

    private[launcher] case object MetaBootstrapFinished

    private[launcher] case object MetaBootstrapTimeout extends TimeoutEvent

    private[launcher] case object MetaSyncFinished

    private[launcher] case object MetaSyncTimeout extends TimeoutEvent

    private[launcher] case object MetaProvisioned

    private[launcher] case object MetaProvisioningTimeout extends TimeoutEvent

  }

}

class LauncherFSM @Inject()( config: LauncherConfig,
                             marClient: MarathonSSEClient,
                             ws: WSClient,
                             gtf: GestaltTaskFactory ) extends LoggingFSM[LauncherState,ServiceData] {

  import LauncherConfig.Services._
  import LauncherConfig.{EXTERNAL_API_CALL_TIMEOUT, EXTERNAL_API_RETRY_INTERVAL, MARATHON_RECONNECT_DELAY}
  import LauncherFSM.Messages._
  import LauncherFSM._
  import States._

  private[this] def sendMessageToSelf[A](delay: FiniteDuration, message: A) = {
    this.context.system.scheduler.scheduleOnce(delay, self, message)
  }

  implicit val apiKeyReads: OFormat[GestaltAPIKey] = Json.format[GestaltAPIKey]

  val marathonBaseUrl: String = config.marathon.baseUrl

  def provisionedDB: GlobalDBConfig = GlobalDBConfig(
    hostname = config.vipHostname(DATA(0)),
    port = DATA(0).port,
    username = config.database.username,
    password = config.database.password,
    prefix = config.database.prefix
  )

  def provisionedDBHostIP: GlobalDBConfig = {
    val useHostIP = for {
      url <- stateData.getUrl(DATA(0), log)
      parts = url.split(":")
      if parts.length == 2
      host = parts(0)
      port <- Try{parts(1).toInt}.toOption
    } yield GlobalDBConfig(
      hostname = host,
      port = port,
      username = config.database.username,
      password = config.database.password,
      prefix = config.database.prefix
    )
    useHostIP getOrElse provisionedDB
  }

  val configuredDB: GlobalDBConfig = GlobalDBConfig(
    hostname = config.database.hostname,
    port = config.database.port,
    username = config.database.username,
    password = config.database.password,
    prefix = config.database.prefix
  )

  val securityInitCredentials = JsObject(
    Seq("username" -> JsString(config.security.username)) ++
      config.security.password.map("password" -> JsString(_))
  )

  val securityProvidedApiKey: Option[GestaltAPIKey] = for {
    key <- config.security.key
    secret <- config.security.secret
  } yield GestaltAPIKey(apiKey = key, apiSecret = Some(secret), accountId = UUID.randomUUID(), disabled = false)

  private[this] def launchApp(service: FrameworkService, globalConfig: GlobalConfig): Unit = {
    val currentState = s"Launching(${service.name})"
    val payload = gtf.getMarathonPayload(service, globalConfig)
    log.debug("'{}' app launch payload:\n{}", service.name, Json.prettyPrint(Json.toJson(payload)))
    val fLaunch = marClient.launchApp(payload) map {
      r =>
        log.info("'{}' app launch response: {}", service.name, r.toString)
        self ! ServiceDeployed(service)
    }
    // launch failed, so we'll never get a task update
    fLaunch.onFailure {
      case e: Throwable =>
        log.warning("error launching {}: {}",service.name,e.getMessage)
        self ! ErrorEvent("Error launching application in Marathon: " + e.getMessage, errorStage = Some(currentState))
    }
  }

  private[this] def initSecurity(secUrl: String): Future[SecurityInitializationComplete] = {
    val initUrl = s"http://${secUrl}/init"
    log.info(s"initializing security at {}",initUrl)
    ws.url(initUrl).withRequestTimeout(EXTERNAL_API_CALL_TIMEOUT).post(securityInitCredentials) flatMap { implicit resp =>
      log.info("security.init response: {}",resp.status)
      log.debug("security.init response body: {}",resp.body)
      resp.status match {
        case 200 =>
          Try{resp.json.as[Seq[GestaltAPIKey]].head} match {
            case Success(key) =>
              Future.successful(SecurityInitializationComplete(key))
            case Failure(_) =>
              Future.failed(new RuntimeException("while initializing security, error extracting API key form security initialization response"))
          }
        case 400 =>
          log.warning("400 from security init, likely that security service already initialized, cannot extract keys to configure downstream services")
          securityProvidedApiKey match {
            case Some(key) =>
              log.info("continuing with API keys from configuration")
              Future.successful(SecurityInitializationComplete(key))
            case None =>
              log.warning("attempting to clear init flag from security database")
              val databaseConfig: GlobalDBConfig = if (config.database.provision) provisionedDBHostIP else configuredDB
              SecurityInitReset(databaseConfig).clearInit()(log)
              Future.failed(new RuntimeException("failed to init security; attempted to clear init flag and will try again"))
          }
        case _ =>
          val msg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
          Future.failed(new RuntimeException(msg))
      }
    }
  }

  private[this] def genRequest(url: String, apiKey: GestaltAPIKey): WSRequest = {
    ws.url(url).withRequestTimeout(EXTERNAL_API_CALL_TIMEOUT).withAuth(apiKey.apiKey,apiKey.apiSecret.get,WSAuthScheme.BASIC)
  }

  private[this] def bootstrapMeta(metaUrl: String, apiKey: GestaltAPIKey) = {
    val initUrl = s"http://${metaUrl}/bootstrap"
    val rootUrl = s"http://${metaUrl}/root"
    for {
      check <- genRequest(rootUrl, apiKey).get()
      done <- if (check.status != 200) {
        log.info("attempting to bootstrap meta")
        genRequest(initUrl, apiKey).post("") flatMap { implicit resp =>
          log.info("meta.bootstrap response: {}",resp.status)
          log.debug("meta.bootstrap response body: {}",resp.body)
          resp.status match {
            case s if Range(200,209).contains(s) => Future.successful(MetaBootstrapFinished)
            case _ => futureFailureWithMessage
          }
        }
      } else {
        log.info("meta appears to already have been bootstrapped; will proceed with next stage")
        Future.successful(MetaBootstrapFinished)
      }
    } yield done
  }

  private[this] def syncMeta(metaUrl: String, apiKey: GestaltAPIKey): Future[MetaSyncFinished.type] = {
    val initUrl = s"http://${metaUrl}/sync"
    log.info(s"syncing meta at {}",initUrl)
    genRequest(initUrl, apiKey).post("") flatMap { implicit resp =>
      log.info("meta.sync response: {}",resp.status)
      log.debug("meta.sync response body: {}",resp.body)
      resp.status match {
        case t if Range(200,299).contains(t) => Future.successful(MetaSyncFinished)
        case _ => futureFailureWithMessage
      }
    }
  }

  private[this] def resourceExistsInList(url: String, apiKey: GestaltAPIKey, name: String): Future[Option[JsValue]] = {
    val matchesName = (js: JsValue) => (js \ "name").asOpt[String].contains(name)
    genRequest(url, apiKey).get() map { implicit resp =>
      log.info(s"meta.get($url) response: {}",resp.status)
      log.debug(s"meta.get($url) response body: {}",resp.body)
      resp.status match {
        case 200 => resp.json match {
          case arr: JsArray => arr.as[Seq[JsValue]].find(matchesName)
          case v: JsValue => if (matchesName(v)) Some(v) else None
        }
        case _ =>
          val msg = getMessageFromResponse
          log.debug(msg)
          None
      }
    }
  }

  private[this] def provisionMetaProviders(metaUrl: String, apiKey: GestaltAPIKey, providerPayloads: Seq[JsValue]): Seq[Future[UUID]] = {
    val url = s"http://${metaUrl}/root/providers"
    providerPayloads.map { payload =>
      val name = (payload \ "name").as[String]
      provisionMetaResource(url, apiKey, "provider", name, payload)
    }
  }

  private[this] def provisionMetaResource(url: String, apiKey: GestaltAPIKey, resourceType: String, name: String, payload: JsValue): Future[UUID] = {
    def getId(js: JsValue): Try[UUID] = Try{ (js \ "id").as[UUID] }

    log.info(s"meta.provision({} '{}') to {}", resourceType, name, url)
    resourceExistsInList(url, apiKey, name) flatMap {
      case Some(js) =>
        log.info("meta.provision({} '{}') already exists", resourceType, name)
        Future.fromTry(getId(js))
      case None =>
        log.debug("meta.provision({} '{}') payload:\n{}", resourceType, name, Json.prettyPrint(payload))
        genRequest(url, apiKey)
          .post(payload)
          .flatMap { implicit resp =>
            log.info("meta.provision({} '{}') response: {}", resourceType, name, resp.status)
            log.debug("meta.provision({} '{}') response body: {}", resourceType, name, resp.body)
            resp.status match {
              case 201 => Future.fromTry(getId(resp.json))
              case _ => futureFailureWithMessage
            }
          }
    }
  }

  private[this] def provisionMetaWorkspace(metaUrl: String, apiKey: GestaltAPIKey, parentFQON: String, name: String, description: String): Future[UUID] = {
    val url = s"http://${metaUrl}/${parentFQON}/workspaces"
    provisionMetaResource(url, apiKey, "workspace", name, Json.obj(
      "name" -> name,
      "description" -> description
    ))
  }

  private[this] def provisionMetaEnvironment(metaUrl: String, apiKey: GestaltAPIKey, parentFQON: String, parentWorkspace: UUID, name: String, description: String, envType: String): Future[UUID] = {
    val url = s"http://${metaUrl}/$parentFQON/workspaces/$parentWorkspace/environments"
    provisionMetaResource(url, apiKey, "environment", name, Json.obj(
      "name" -> name,
      "description" -> description,
      "properties" -> Json.obj(
        "environment_type" -> envType
      )
    ))
  }

  private[this] def provisionDemoLambdas(metaUrl: String, metaApiUrl: String, apiKey: GestaltAPIKey, parentEnv: UUID, providerId: UUID): Seq[Future[UUID]] = {
    val url = s"http://${metaUrl}/root/environments/$parentEnv/lambdas"
    val env = Map(
      "API_KEY"    -> apiKey.apiKey,
      "API_SECRET" -> apiKey.apiSecret.getOrElse(""),
      "META_URL"   -> metaApiUrl
    )
    val setupLambda = Json.obj(
      "name" -> "demo-setup",
      "description" -> "Lambda to setup demo environment",
      "properties" -> Json.obj(
        "runtime" -> "nodejs",
        "code_type" -> "package",
        "package_url" -> LauncherConfig.MetaConfig.SETUP_LAMBDA_URL,
        "handler" -> "demo-setup.js;run",
        "compressed" -> false,
        "public" -> true,
        "cpus" -> 0.2,
        "memory" -> 512,
        "timeout" -> 120,
        "headers" -> Json.obj(
          "Accept" -> "text/plain"
        ),
        "env" -> env,
        "provider" -> Json.obj(
          "id" -> providerId,
          "locations" -> Json.arr()
        )
      )
    )
    val teardownLambda = Json.obj(
      "name" -> "demo-teardown",
      "description" -> "Lambda to tear down demo environment",
      "properties" -> Json.obj(
        "runtime" -> "nodejs",
        "code_type" -> "package",
        "package_url" -> LauncherConfig.MetaConfig.TDOWN_LAMBDA_URL,
        "handler" -> "demo-teardown.js;run",
        "compressed" -> false,
        "public" -> true,
        "cpus" -> 0.2,
        "memory" -> 512,
        "timeout" -> 120,
        "headers" -> Json.obj(
          "Accept" -> "text/plain"
        ),
        "env" -> env,
        "headers" -> Json.obj(),
        "provider" -> Json.obj(
          "id" -> providerId,
          "locations" -> Json.arr()
        )
      )
    )
    Seq(setupLambda,teardownLambda).map { payload =>
      val name = (payload \ "name").as[String]
      provisionMetaResource(url, apiKey, "lambda", name, payload)
    }
  }

  private[this] def provisionApi( metaUrl: String,
                                  apiKey: GestaltAPIKey,
                                  parentEnv: UUID,
                                  gatewayProvider: UUID,
                                  kongProvider: UUID,
                                  name: String ): Future[UUID] = {
    val url = s"http://${metaUrl}/root/environments/$parentEnv/apis"
    provisionMetaResource(url, apiKey, "api", name, Json.obj(
      "description" -> "API for exposing Demo setup/teardown lambdas",
      "name" -> "demo",
      "properties" -> Json.obj(
        "provider" -> Json.obj(
          "id" -> gatewayProvider.toString,
          "locations" -> Json.arr(kongProvider.toString)
        )
      )
    ))
  }

  private[this] def provisionEndpoint( metaUrl: String,
                                       apiKey: GestaltAPIKey,
                                       parentApi: UUID,
                                       name: String,
                                       lambdaId: UUID ): Future[UUID] = {
    val url = s"http://${metaUrl}/root/apis/$parentApi/apiendpoints"
    provisionMetaResource(url, apiKey, "apiendpoint", name, Json.obj(
      "name" -> ("-" + name),
      "properties" -> Json.obj(
        "implementation_type" -> "lambda",
        "implementation_id" -> lambdaId.toString,
        "resource" -> ("/" + name),
        "synchronous" -> true
      )
    ))
  }

  private[this] def provisionDemo( metaUrl: String,
                                   apiKey: GestaltAPIKey,
                                   laserProvider: UUID,
                                   gatewayProvider: UUID,
                                   kongProvider: UUID ): Future[MetaProvisioned.type] =
  {
    val metaApiUrl = "http://" + config.vipHostname(META) + ":" + META.port
    for {
      wrkId <- provisionMetaWorkspace(metaUrl, apiKey, "root", "demo", "Demo")
      envId <- provisionMetaEnvironment(metaUrl, apiKey, "root", wrkId, "demo", "Demo", "production")
      Seq(setupLambdaId,tdownLambdaId) <- Future.sequence(provisionDemoLambdas(metaUrl, metaApiUrl, apiKey, envId, laserProvider))
      demoApiId <- provisionApi(metaUrl, apiKey, envId, gatewayProvider, kongProvider, "demo")
      _ <- Future.sequence(Seq(
         provisionEndpoint(metaUrl, apiKey, demoApiId, "setup",    setupLambdaId),
         provisionEndpoint(metaUrl, apiKey, demoApiId, "teardown", tdownLambdaId)
      ))
    } yield MetaProvisioned
  }

  private[this] def getMessageFromResponse(implicit response: WSResponse) = {
    Try{(response.json \ "message").as[String]}.getOrElse(response.body)
  }

  private[this] def futureFailureWithMessage(implicit response: WSResponse) = {
    val msg = getMessageFromResponse
    Future.failed(new RuntimeException(msg))
  }

  private[this] def renameMetaRootOrg(metaUrl: String, apiKey: GestaltAPIKey): Future[MetaProvisioned.type] = {
    genRequest(s"http://$metaUrl/root", apiKey)
      .patch(Json.toJson(Seq(PatchOp.Replace(
        path = "/description",
        value = config.meta.companyName
      ))))
      .flatMap { implicit resp =>
        log.info("meta.root rename response: {}",resp.status)
        log.debug("meta.root rename response body: {}", resp.body)
        resp.status match {
          case 200 => Future.successful(MetaProvisioned)
          case _ => futureFailureWithMessage
        }
      }
  }

  private[this] def configureCaaSProviderWithLoggingProvider(metaUrl: String, apiKey: GestaltAPIKey, dcosProviderId: UUID, loggingProviderId: UUID): Future[Unit] = {
    genRequest(s"http://$metaUrl/root/providers/${dcosProviderId}", apiKey)
      .patch(Json.toJson(Seq(PatchOp.Replace(
        path = "/properties/linked_providers",
        value = Json.arr(Json.obj(
          "id" -> loggingProviderId.toString,
          "name" -> "logging",
          "typeId" -> "e1782fef-4b7c-4f75-b8b8-6e6e2ecd82b2",
          "type" -> "Gestalt::Configuration::Provider::Logging"
        ))
      ))))
      .flatMap { implicit resp =>
        log.info("meta.dcosProvider patch response: {}", resp.status)
        log.debug("meta.dcosProvider patch response body: {}", resp.body)
        resp.status match {
          case r if 200 <= r && r <= 299 => Future.successful(())
          case _ => futureFailureWithMessage
        }
      }
  }

  private[this] def provisionMetaLicense(metaUrl: String, apiKey: GestaltAPIKey): Future[MetaProvisioned.type] = {
    val licenseBody = Json.obj(
      "name" -> "Default-License-1",
      "description" -> "Default GF license",
      "properties" -> Json.obj(
        "data" -> "ABwwGgQUVS9uKF73AyeP06JhgH17LNOBxB8CAgQAPEWRItlr1xdoJnalqW2vcmujpdB2WfCG3ShwuovCtFO/mhMmhktOwYDl6xBKihC8lBhwLeAtyi61R2b6UwTSkMiRqyIdcRMGc+Eqozm3XanhVpCXATsUawQFfqGdu/rUVFCGRq1J129gwJ1EVVtYab1GvFjib30c8ZInWLI0PBazyZZyda9f5xdys2mqeLtSJDI3960c1xwBQJyRvXIG9ZZgceU0koG/5kF8+I/4fUrkjzSKdcy6iXgTIDyC+i3bictfaT2X3Yhoo63PSlXU4IFrZ8CMtWOmffpahmuwCBGPq0VIuvSVnZzYNw8yN/d8lbMarXp2wjU/Kd2HRP1nZ57l8GXF2H2iKj4rPGLAtNn8XYFPh7jviWD8RSS8eeeWMVPCaHsza10G8jhonLZEsAB+fkQdmb5R"
      )
    )
    val licenseUrl = s"http://${metaUrl}/root/licenses"
    genRequest(licenseUrl, apiKey).post(licenseBody) flatMap { implicit resp =>
      log.info("meta.license response: {}",resp.status)
      log.debug("meta.license response body: {}",resp.body)
      resp.status match {
        case 201 => Future.successful(MetaProvisioned)
        case _ => futureFailureWithMessage
      }
    }
  }

  private[launcher] def nextState(state: LauncherState): LauncherState = {
    val cur = config.LAUNCH_ORDER.indexOf(state)
    if (config.LAUNCH_ORDER.isDefinedAt(cur+1)) config.LAUNCH_ORDER(cur+1) else Error
  }

  object FrameworkServiceFromAppId {
    private [this] val appIdWithGroup = s"/${config.marathon.appGroup}/(.*)".r
    def unapply(appId: String): Option[FrameworkService] = appIdWithGroup.unapplySeq(appId)
      .flatMap(_.headOption)
      .flatMap(LauncherConfig.Services.fromName)
  }

  object FrameworkServiceFromDeploymentId {
    def unapply(deploymentId: String): Option[FrameworkService] = None
  }

  def requestUpdateAndStay(service: FrameworkService): State = {
    marClient.getServiceStatus(service).onComplete {
      case Success(status) =>
        self ! UpdateServiceInfo(status)
      case Failure(ex) =>
        log.warning("error retrieving app status from Marathon: {}",ex.getMessage)
    }
    stay
  }

  def isServiceActive(si: ServiceInfo): Boolean = {si.status == RUNNING || si.status == HEALTHY}

  def advanceState(newData: ServiceData): State = {
    stateName match {
      case state: LaunchingState if newData.statuses.get(state.targetService).exists(isServiceActive) =>
        goto(nextState(state)) using newData
      case _ =>
        stay using newData
    }
  }

  /*************************************************************************************
    *
    * the finite state machine
    *
    *************************************************************************************/

  startWith(Uninitialized, ServiceData.init.copy(
    globalConfig = GlobalConfig()
      .withDb(
        if (config.database.provision) provisionedDB else configuredDB
      )
  ))

  when(Uninitialized) {
    // this is a bootstrap situation... we need to get the complete state and connect the Marathon event bus
    case Event(LaunchServicesRequest,d) =>
      self ! OpenConnectionToMarathonEventBus
      stay
    // the Marathon event bus connection will trigger this (or an error), but we won't proceed to launching until we get it
    case Event(UpdateAllServiceInfo(all), d) =>
      log.info("initializing all services")
      all.foreach {
        svcInfo => log.info(s"${svcInfo.service} : ${svcInfo.status}")
      }
      this.context.system.scheduler.schedule(1 minute, 1 minute, self, Sync)
      if (config.database.provision) {
        goto(config.LAUNCH_ORDER.head) using d.update(all)
      } else {
        goto(LaunchingRabbit) using d.update(all)
      }
    case Event(ShutdownRequest(_),d) =>
      log.info("Ignoring ShutdownRequest from Uninitialized state")
      stay
  }

  when(ShuttingDown) {
    // this is similar to above, but we assume that we've already been initialized so we can proceed directly to launching
    case Event(LaunchServicesRequest,d) =>
      if (config.database.provision) {
        goto(config.LAUNCH_ORDER.head)
      } else {
        goto(LaunchingRabbit)
      }
  }

  onTransition {
    // general debug printing
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

 /**************************************************
    *
    * service launch stages
    *
    *************************************************/

  onTransition {
    case (_, stage: LaunchingState) =>
      log.info(s"transitioning to ${stage}")
      log.info(s"current service status is ${nextStateData.statuses.get(stage.targetService)}")
      if (nextStateData.statuses.get(stage.targetService).exists(isServiceActive)) {
        log.info(s"${stage.targetService} is already active, will not relaunch and will attempt to advance stage")
        self ! AdvanceStage
      } else {
        log.info(s"${stage.targetService} is not active, will launch")
        launchApp(stage.targetService, nextStateData.globalConfig)
      }
  }

  /**************************************************
    *
    * post-launch stages: configuration stages
    *
    *************************************************/

  onTransition {
    case _ -> RetrievingAPIKeys =>
      nextStateData.getUrl(SECURITY, log) match {
        case None => self ! ErrorEvent("while initializing security, missing security URL after launching security", Some(RetrievingAPIKeys.toString))
        case Some(secUrl) => initSecurity(secUrl) onComplete {
          case Success(msg) => self ! msg
          case Failure(ex) =>
            log.warning("error initializing security service: {}",ex.getMessage)
            // keep retrying until our time runs out and we leave this state
            sendMessageToSelf(EXTERNAL_API_RETRY_INTERVAL, RetryRequest(RetrievingAPIKeys))
        }
      }
    case _ -> BootstrappingMeta =>
      (nextStateData.getUrl(META, log), nextStateData.adminKey) match {
        case (None,_) => self ! ErrorEvent("while bootstrapping meta, missing meta URL after launching meta", Some(BootstrappingMeta.toString))
        case (_,None) => self ! ErrorEvent("while bootstrapping meta, missing admin API key after initializing security", Some(BootstrappingMeta.toString))
        case (Some(metaUrl),Some(apiKey)) => bootstrapMeta(metaUrl, apiKey) onComplete {
          case Success(msg) => self ! msg
          case Failure(ex) =>
            log.warning("error bootstrapping meta service: {}",ex.getMessage)
            // keep retrying until our time runs out and we leave this state
            sendMessageToSelf(EXTERNAL_API_RETRY_INTERVAL, RetryRequest(BootstrappingMeta))
        }
      }
    case _ -> SyncingMeta =>
      (nextStateData.getUrl(META, log), nextStateData.adminKey) match {
        case (None,_) => self ! ErrorEvent("while syncing meta, missing meta URL after launching meta", Some(SyncingMeta.toString))
        case (_,None) => self ! ErrorEvent("while syncing meta, missing admin API key after initializing security", Some(SyncingMeta.toString))
        case (Some(metaUrl),Some(apiKey)) => syncMeta(metaUrl, apiKey) onComplete {
          case Success(msg) => self ! msg
          case Failure(ex) =>
            log.warning("error syncing meta service: {}",ex.getMessage)
            // keep retrying until our time runs out and we leave this state
            sendMessageToSelf(EXTERNAL_API_RETRY_INTERVAL, RetryRequest(SyncingMeta))
        }
      }
    case _ -> ProvisioningMeta =>
      (nextStateData.getUrl(META, log), nextStateData.adminKey) match {
        case (Some(metaUrl),Some(apiKey)) =>
          val gc = nextStateData.globalConfig
          val baseProviderPayloads = Seq(
            gtf.getCaasProvider(),
            gtf.getDbProvider(gc.dbConfig.get),
            gtf.getRabbitProvider(),
            gtf.getSecurityProvider(gc.secConfig.get)
          )
          val enabledExecutorPayloads = config.laser.enabledRuntimes map gtf.getExecutorProvider
          val fMetaProvisioning = for {
            configOnlyProviderIds <- Future.sequence(
              provisionMetaProviders(metaUrl,apiKey, baseProviderPayloads ++ enabledExecutorPayloads)
            )
            (Seq(dcosProviderId,dbProviderId,rabbitProviderId,secProviderId),laserExecutorIds) = configOnlyProviderIds.splitAt(4)
            //
            systemWorkspaceId <- provisionMetaWorkspace(metaUrl, apiKey, "root", "gestalt-system-workspace", "Gestalt System Workspace")
            _                 <- provisionMetaEnvironment(metaUrl, apiKey, "root", systemWorkspaceId, "gestalt-system-environment", "Gestalt System Environment", "production")
            laserEnvId        <- provisionMetaEnvironment(metaUrl, apiKey, "root", systemWorkspaceId, "gestalt-laser-environment", "Gestalt Laser Environment", "production")
            //
            Seq(laserProviderId,kongProviderId) <- Future.sequence(
              provisionMetaProviders(metaUrl,apiKey, Seq(
                gtf.getLaserProvider(apiKey, dbProviderId, rabbitProviderId, secProviderId, dcosProviderId, laserExecutorIds, laserEnvId),
                gtf.getKongProvider(dbProviderId, dcosProviderId)
              ))
            )
            maybeLogProvider <- Future.sequence(
              provisionMetaProviders(metaUrl,apiKey, gtf.getLogProvider(dcosProviderId).toSeq )
            )
            _ <- Future.sequence(maybeLogProvider.map(
              logProviderId => configureCaaSProviderWithLoggingProvider(metaUrl, apiKey, dcosProviderId, logProviderId)
            ))
            //
            Seq(policyProviderId,gtwProviderId) <- Future.sequence(
              provisionMetaProviders(metaUrl,apiKey, Seq(
                gtf.getPolicyProvider(apiKey, dcosProviderId, laserProviderId, rabbitProviderId),
                gtf.getGatewayProvider(dbProviderId, secProviderId, kongProviderId, dcosProviderId)
              ))
            )
            //
            _ <- Future.sequence(Seq(
              renameMetaRootOrg(metaUrl,apiKey),
              // provisionDemo(metaUrl, apiKey, laserProvider = laserProviderId, gatewayProvider = gtwProviderId, kongProvider = kongProviderId),
              provisionMetaLicense(metaUrl,apiKey)
            ))
          } yield MetaProvisioned
          fMetaProvisioning onComplete {
            case Success(msg) => self ! msg
            case Failure(ex) =>
              log.warning("error provisioning resources in meta service: {}", ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(EXTERNAL_API_RETRY_INTERVAL, RetryRequest(ProvisioningMeta))
          }
        case (None,_) => self ! ErrorEvent("while provisioning resources in meta, missing meta URL after launching meta", Some(SyncingMeta.toString))
        case (_,None) => self ! ErrorEvent("while provisioning resources in meta, missing admin API key after initializing security", Some(SyncingMeta.toString))
      }
  }

  private[this] def standardWhen(state: LaunchingState) = when(state) {
    case Event(UpdateServiceInfo(status), d) =>
      advanceState(d.update(status))

    case Event(UpdateAllServiceInfo(all), d) =>
      // in case of a reconnect to the marathon event bus during launch, a full status update may be
      // sufficient to trigger moving to the next stage
      advanceState(d.update(all))
  }

  config.LAUNCH_ORDER.collect({case s: LaunchingState => s}).foreach(standardWhen)

  /**************************************************
    *
    * configuration stage timeouts
    * only perform on first transition
    *
    *************************************************/

  onTransition {
    case prev -> RetrievingAPIKeys         if prev != RetrievingAPIKeys         => sendMessageToSelf(5.minutes, APIKeyTimeout)
    case prev -> BootstrappingMeta         if prev != BootstrappingMeta         => sendMessageToSelf(5.minutes, MetaBootstrapTimeout)
    case prev -> SyncingMeta               if prev != SyncingMeta               => sendMessageToSelf(5.minutes, MetaSyncTimeout)
    case prev -> ProvisioningMeta          if prev != ProvisioningMeta          => sendMessageToSelf(5.minutes, MetaProvisioningTimeout)
  }

  when(RetrievingAPIKeys) {
    case Event(RetryRequest(RetrievingAPIKeys), d) =>
      goto(RetrievingAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
      log.debug("received apiKey:\n{}",Json.prettyPrint(Json.toJson(apiKey)))
      goto(nextState(stateName)) using d.copy(
        adminKey = Some(apiKey),
        globalConfig = d.globalConfig.copy(
          secConfig = Some(GlobalSecConfig(
            hostname = config.vipHostname(SECURITY),
            port = SECURITY.port,
            apiKey = apiKey.apiKey,
            apiSecret = apiKey.apiSecret.get,
            realm = config.marathon.tld.map("https://security." + _)
              .orElse(Some(s"http://${gtf.vipDestination(SECURITY)}"))
          ))
        )
      )
    case Event(APIKeyTimeout, d) =>
      val msg = "timed out waiting for initialization of gestalt-security and retrieval of administrative API keys"
      log.error(msg)
      goto(Error) using d.copy(
        error = Some(msg)
      )
  }

  when(BootstrappingMeta) {
    case Event(RetryRequest(BootstrappingMeta), d) =>
      goto(BootstrappingMeta)
    case Event(MetaBootstrapFinished, d) =>
      goto(nextState(stateName))
    case Event(MetaBootstrapTimeout, d) =>
      val msg = "timed out waiting for bootstrap of gestalt-meta"
      log.error(msg)
      goto(Error) using d.copy(
        error = Some(msg)
      )
  }

  when(SyncingMeta) {
    case Event(RetryRequest(SyncingMeta), d) =>
      goto(SyncingMeta)
    case Event(MetaSyncFinished, d) =>
      goto(nextState(stateName))
    case Event(MetaSyncTimeout, d) =>
      val msg = "timed out waiting for sync of gestalt-meta"
      log.error(msg)
      goto(Error) using d.copy(
        error = Some(msg)
      )
  }

  when(ProvisioningMeta) {
    case Event(RetryRequest(ProvisioningMeta), d) =>
      goto(ProvisioningMeta)
    case Event(MetaProvisioned, d) =>
      goto(nextState(stateName))
    case Event(MetaProvisioningTimeout, d) =>
      val msg = "timed out provisioning gestalt-meta"
      log.error(msg)
      goto(Error) using d.copy(
        error = Some(msg)
      )
  }

  when(AllServicesLaunched)(FSM.NullFunction)
  when(Error)(FSM.NullFunction)

  whenUnhandled {

    /**************************************************
      *
      * stage advancement request
      * from an attempt to launch something that was already running
      *
      *************************************************/

    case Event(AdvanceStage, d) =>
      advanceState(d)

    /**************************************************
      *
      * marathon server-sent-event bus
      *
      *************************************************/

    case Event(OpenConnectionToMarathonEventBus, d) =>
      if (d.connected) {
        log.info("ignoring request OpenConnectionToMarathonEventBus, I think I'm already connected")
      } else {
        log.info("attempting to connect to Marathon event bus")
        marClient.connectToBus(self)
      }
      stay

    case Event(MarathonSSEClient.Connected, d) =>
      log.info("successfully connected to Marathon event bus, requesting service update")
      val s = self
      marClient.getServices() onComplete {
        case Failure(t) =>
          log.error(t, "error getting service statuses from Marathon REST API")
        case Success(all) =>
          log.info(s"received info on ${all.size} services, sending to self to update status")
          s ! UpdateAllServiceInfo(all)
      }
      stay using d.copy(
        connected = true
      )

    case Event(Status.Failure(t), d) =>
      val sw = new StringWriter
      t.printStackTrace(new PrintWriter(sw))
      log.error("received Failure status from Marathon event bus, will attempt to reconnect: {}",sw.toString)
      sendMessageToSelf(MARATHON_RECONNECT_DELAY, OpenConnectionToMarathonEventBus)
      stay using d.copy(
        connected = false
      )

    case Event(sse @ ServerSentEvent(Some(_), Some(eventType), _, _), d) =>
      val msg = eventType match {
        case "app_terminated_event"        => MarathonSSEClient.parseEvent[MarathonAppTerminatedEvent](sse)
        case "health_status_changed_event" => MarathonSSEClient.parseEvent[MarathonHealthStatusChange](sse)
        case "status_update_event"         => MarathonSSEClient.parseEvent[MarathonStatusUpdateEvent](sse)
        case _ => {
          log.debug(s"ignoring event ${eventType}")
          None
        }
      }
      msg.foreach(sse => self ! sse)
      stay

    case Event(_ : ServerSentEvent, d) =>
      log.debug("received server-sent-event heartbeat from marathon event bus")
      stay

    case Event(e @ MarathonAppTerminatedEvent(FrameworkServiceFromAppId(service),_,_), d) =>
      log.info(s"received ${e.eventType} for service ${service.name}")
      stay using d.update(ServiceInfo(
        service = service,
        vhosts = Seq.empty,
        hostname = None,
        ports = Seq.empty,
        status = NOT_FOUND
      ))

    case Event(e @ MarathonAppTerminatedEvent(nonFrameworkAppId,_,_), d) =>
      log.debug(s"received ${e.eventType} for non-framework service ${nonFrameworkAppId}")
      stay


    case Event(e @ MarathonHealthStatusChange(_, _, FrameworkServiceFromAppId(service), maybeTaskId, maybeInstanceId, _, alive), d) =>
      log.info(s"received MarathonHealthStatusChange(${maybeTaskId orElse maybeInstanceId}.alive == ${alive}) for task belonging to ${service.name}")
      val updatedStatus = d.statuses.get(service).map(
        _.copy(status = if (alive) HEALTHY else UNHEALTHY)
      )
      updatedStatus.foreach(info => log.info(s"marking ${info.service} as ${info.status}"))
      stay using d.update(updatedStatus.toSeq)

    case Event(e @ MarathonStatusUpdateEvent(_, _, taskStatus, _, FrameworkServiceFromAppId(service), _, _, _, _, _, _) , d) =>
      log.info(s"received StatusUpdateEvent(${taskStatus}) for task belonging to ${service.name}")
      requestUpdateAndStay(service)

    case Event(e @ MarathonStatusUpdateEvent(_, _, taskStatus, _, nonFrameworkAppId, _, _, _, _, _, _) , d) =>
      log.debug(s"ignoring StatusUpdateEvent(${taskStatus}) for task from non-framework app ${nonFrameworkAppId}")
      stay

    /**************************************************
      *
      * miscellaneous events, mostly updates to state
      *
      *************************************************/

    case Event(Sync, _) => {
      log.info("performing periodic sync of apps in marathon")
      val s = self
      marClient.getServices() onComplete {
        case Failure(t) =>
          log.error(t, "error getting service statuses from Marathon REST API")
        case Success(all) =>
          log.info(s"received info on ${all.size} services, sending to self to update status")
          s ! UpdateAllServiceInfo(all)
      }
      stay
    }

    case Event(UpdateServiceInfo(status), d) =>
      stay using d.update(status)

    case Event(UpdateAllServiceInfo(all), d) =>
      stay using d.update(all)

    case Event(ServiceDeployed(service), d) =>
      // this come from a successful completion of the future in launchApp
      requestUpdateAndStay(service) using d.update(ServiceInfo(
        service = service,
        vhosts = Seq.empty,
        hostname = None,
        ports = Seq.empty,
        status = LAUNCHING
      ))

    case Event(ServiceDeleting(service), d) =>
      // this came from a successful DELETE call against the marathon REST API
      // it does not mean that the service is no longer running, just that marathon accepted our request with a 200
      // the actual task may have already been deleted by the time we process this, so only update it if it exists and isn't already marked NOT_FOUND
      log.info(d.statuses.get(service).toString)
      stay using d.update(
        d.statuses.get(service).filter(_.status != NOT_FOUND).map(_.copy(status = DELETING)).toSeq
      )

    case Event(ErrorEvent(message,errorStage),d) => {
      log.error(message)
      goto(Error) using d.copy(
        error = Some(message),
        errorStage = errorStage
      )
    }

    case Event(LaunchServicesRequest,d) =>
      // we only recognize this request while in Uninitialized or ShuttingDown
      log.info("ignoring LauncherServicesRequest in stage " + stateName)
      stay

    case Event(ShutdownRequest(shutdownDB),d) =>
      val s = self
      val deleteApps = config.LAUNCH_ORDER
        .collect({case s: LaunchingState => s.targetService})
        .filter { svc => shutdownDB || !svc.isInstanceOf[DATA] }
        .reverse
      deleteApps.foreach {
        service => marClient.killApp(service) onComplete {
          case Success(true)  => s ! ServiceDeleting(service)
          case Success(false) => log.info(s"marathon app for ${service} did not exist, will not update launcher status")
          case Failure(t)     => log.error(t, s"error deleting marathon app for ${service} during shutdown")
        }
      }
      goto(ShuttingDown) using d.copy(
        error = None,
        errorStage = None
      ) replying ShutdownAcceptedResponse

    case Event(StatusRequest,d) =>
      val stage = stateName match {
        case Error => d.errorStage.map("Error during ".+).getOrElse("Error")
        case _ => stateName.toString
      }
      val services = config.provisionedServices.map(service =>
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

    case Event(t: TimeoutEvent, d) =>
      stay

    case Event(rr @ RetryRequest(_), d) =>
      log.info(s"ignoring ${rr}")
      stay

    case Event(e, d) =>
      log.warning("unhandled event of type " + e.getClass.toString)
      stay
  }

  initialize()
}
