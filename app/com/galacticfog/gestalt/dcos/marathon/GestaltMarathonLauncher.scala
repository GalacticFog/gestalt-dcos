package com.galacticfog.gestalt.dcos.marathon

import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM}
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Try}
import com.galacticfog.gestalt.dcos.marathon._

sealed trait LauncherState {
  def next: LauncherState
  def targetService: String
}
// in order...
case object Uninitialized       extends LauncherState {def next = LaunchingDB;          def targetService = ""}
case object LaunchingDB         extends LauncherState {def next = LaunchingSecurity;    def targetService = "data"}
case object LaunchingSecurity   extends LauncherState {def next = WaitingForAPIKeys;    def targetService = "security"}
case object WaitingForAPIKeys   extends LauncherState {def next = LaunchingMeta;        def targetService = ""}
case object LaunchingMeta       extends LauncherState {def next = BootstrappingMeta;    def targetService = "meta"}
case object BootstrappingMeta   extends LauncherState {def next = SyncingMeta;          def targetService = ""}
case object SyncingMeta         extends LauncherState {def next = LaunchingApiProxy;    def targetService = ""}
case object LaunchingApiProxy   extends LauncherState {def next = LaunchingUI;          def targetService = "api-proxy"}
case object LaunchingUI         extends LauncherState {def next = LaunchingRabbit;      def targetService = "ui"}
case object LaunchingRabbit     extends LauncherState {def next = LaunchingKong;        def targetService = "rabbit"}
case object LaunchingKong       extends LauncherState {def next = LaunchingApiGateway;  def targetService = "kong"}
case object LaunchingApiGateway extends LauncherState {def next = LaunchingLambda;      def targetService = "api-gateway"}
case object LaunchingLambda     extends LauncherState {def next = LaunchingPolicy;      def targetService = "lambda"}
case object LaunchingPolicy     extends LauncherState {def next = AllServicesLaunched;  def targetService = "policy"}
case object AllServicesLaunched extends LauncherState {def next = Error;                def targetService = ""}
// failure
case object ShuttingDown        extends LauncherState {def next = ShuttingDown;         def targetService = ""}
case object Error               extends LauncherState {def next = Error;                def targetService = ""}

final case class ServiceData(securityUrl: Option[String],
                             metaUrl: Option[String],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String],
                             errorStage: Option[String])
case object ServiceData {
  def empty: ServiceData = ServiceData(None,None,None,None,None)
}

case object StatusRequest
case object LaunchServicesRequest
case object ShutdownRequest
case object RetryRequest
final case class ErrorEvent(message: String, errorStage: Option[String])
final case class SecurityInitializationComplete(key: GestaltAPIKey)
case object MetaBootstrapFinished
case object MetaSyncFinished

final case class StatusResponse(launcherStage: String, error: Option[String])

class GestaltMarathonLauncher @Inject()(config: Configuration,
                                        marClient: MarathonSSEClient,
                                        wsclient: WSClient,
                                        gtf: GestaltTaskFactory) extends LoggingFSM[LauncherState,ServiceData] {

  def getString(path: String, default: String): String = config.getString(path).getOrElse(default)

  def getInt(path: String, default: Int): Int = config.getInt(path).getOrElse(default)

  def sendMessageToSelf[A](delay: FiniteDuration, message: A) = {
    this.context.system.scheduler.scheduleOnce(delay, self, message)
  }

  implicit val apiKeyReads = Json.format[GestaltAPIKey]

  val marathonBaseUrl = config.getString("marathon.url") getOrElse "http://marathon.mesos:8080"

  val appGroup = getString("marathon.appGroup", GestaltTaskFactory.DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/")

  val tld = config.getString("marathon.tld").map(tld => Json.obj("tld" -> tld)).getOrElse(Json.obj())

  // setup a-priori/static globals
  val globals = Json.obj(
    "marathon" -> Json.obj(
      "appGroup" -> appGroup
    ).++(tld),
    "database" -> Json.obj(
      "hostname" -> getString("database.hostname", "10.99.99.10"),
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

  private def standardWhen(state: LauncherState) = when(state) {
    case Event(e @ MarathonStatusUpdateEvent(_, _, "TASK_RUNNING", _, appId, _, _, _, _, _, _) , d) if appId == s"/${appGroup}/${state.targetService}" =>
      log.info(s"${state.targetService} running")
      goto(state.next)
  }

  startWith(Uninitialized, ServiceData.empty)

  when(Uninitialized) {
    case Event(LaunchServicesRequest,d) =>
      goto(LaunchingDB)
  }

  // service launch stages
  onTransition {
    case _ -> stage if stage.targetService.nonEmpty => launchApp(stage.targetService, nextStateData.adminKey)
  }

  // post-launch stages
  onTransition {
    case _ -> WaitingForAPIKeys =>
      nextStateData.securityUrl match {
        case None => sendMessageToSelf(0.seconds, ErrorEvent("while initializing security, missing security URL after launching security", Some(WaitingForAPIKeys.toString)))
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
      (nextStateData.metaUrl,nextStateData.adminKey) match {
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
      (nextStateData.metaUrl,nextStateData.adminKey) match {
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
  }

  standardWhen(LaunchingDB)
  standardWhen(LaunchingRabbit)
  standardWhen(LaunchingKong)
  standardWhen(LaunchingApiGateway)
  standardWhen(LaunchingLambda)
  standardWhen(LaunchingUI)
  standardWhen(LaunchingApiProxy)
  standardWhen(LaunchingPolicy)

  when(LaunchingSecurity) {
    case Event(e @ MarathonStatusUpdateEvent(_, _, "TASK_RUNNING", _, appId, host, ports, _, _, _, _) , d) if appId == s"/${appGroup}/${LaunchingSecurity.targetService}" =>
      log.info("security running")
      sendMessageToSelf(5.minutes, StateTimeout)
      goto(stateName.next) using d.copy(
        securityUrl = Some(s"${host}:${ports.headOption.getOrElse(9455)}")
      )
  }

  when(LaunchingMeta) {
    case Event(e @ MarathonStatusUpdateEvent(_, _, "TASK_RUNNING", _, appId, host, ports, _, _, _, _) , d) if appId == s"/${appGroup}/${LaunchingMeta.targetService}" =>
      log.info("meta running")
      sendMessageToSelf(5.minutes, StateTimeout)
      goto(stateName.next) using d.copy(
        metaUrl = Some(s"${host}:${ports.headOption.getOrElse(14374)}")
      )
  }

  when(WaitingForAPIKeys) {
    case Event(RetryRequest, d) =>
      goto(WaitingForAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
      log.debug("received apiKey:\n{}",Json.prettyPrint(Json.toJson(apiKey)))
      goto(stateName.next) using d.copy(
        adminKey = Some(apiKey)
      )
    case Event(StateTimeout, d) =>
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
      goto(stateName.next)
    case Event(StateTimeout, d) =>
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
      goto(stateName.next)
    case Event(StateTimeout, d) =>
      val mesg = "timed out waiting for bootstrap of gestalt-meta"
      log.error(mesg)
      goto(Error) using d.copy(
        error = Some(mesg)
      )
  }

  when(AllServicesLaunched)(FSM.NullFunction)
  when(Error)(FSM.NullFunction)

  when(ShuttingDown) {
    case Event(e, _) =>
      log.info(s"ignoring event because of shutdown request: ${e.getClass.getName}")
      stay
  }

  whenUnhandled {
    case Event(ShutdownRequest,d) =>
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
  }

  onTransition {
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

  initialize()
}
