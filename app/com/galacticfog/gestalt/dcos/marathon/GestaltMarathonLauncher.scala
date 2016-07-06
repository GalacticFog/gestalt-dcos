package com.galacticfog.gestalt.dcos.marathon

import javax.inject.Inject

import akka.actor.{FSM, LoggingFSM}
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.WSClient
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
case object WaitingForAPIKeys   extends LauncherState {def next = LaunchingRabbit;      def targetService = ""}
case object LaunchingRabbit     extends LauncherState {def next = LaunchingMeta;        def targetService = "rabbit"}
case object LaunchingMeta       extends LauncherState {def next = AllServicesLaunched;  def targetService = "meta"}
case object AllServicesLaunched extends LauncherState {def next = Error;                def targetService = ""}
// failure
case object ShuttingDown        extends LauncherState {def next = ShuttingDown;         def targetService = ""}
case object Error               extends LauncherState {def next = Error;                def targetService = ""}

final case class ServiceData(securityUrl: Option[String],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String])
case object ServiceData {
  def empty: ServiceData = ServiceData(None,None,None)
}

case object StatusRequest
case object LaunchServicesRequest
case object ShutdownRequest
case object RetryRequest
final case class ErrorEvent(message: String)
final case class SecurityInitializationComplete(key: GestaltAPIKey)

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

  val appGroup = getString("marathon.appGroup", "gestalt").stripPrefix("/").stripSuffix("/")

  // setup a-priori/static globals
  val globals = Json.obj(
    "marathon" -> Json.obj(
      "appGroup" -> appGroup
    ),
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
    val allConfig = apiKey.map(apiKey => Json.obj(
      "security" -> Json.obj(
        "apiKey" -> apiKey.apiKey,
        "apiSecret" -> apiKey.apiSecret
      )
    )).fold(globals)(sec => sec ++ globals)
    val payload = gtf.getMarathonPayload(name, allConfig)
    marClient.launchApp(payload) map {
      r => log.info(s"'${name}' launch response: " + r.toString())
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

  onTransition {
    case _ -> LaunchingDB       => launchApp(LaunchingDB.targetService)
    case _ -> LaunchingSecurity => launchApp(LaunchingSecurity.targetService)
    case _ -> LaunchingRabbit   => launchApp(LaunchingRabbit.targetService)
    case _ -> LaunchingMeta     => launchApp(LaunchingMeta.targetService)
  }

  onTransition {
    case _ -> WaitingForAPIKeys =>
      nextStateData.securityUrl match {
        case None => sendMessageToSelf(0.seconds, ErrorEvent("missing security URL after launching security"))
        case Some(secUrl) =>
          val initUrl = s"http://${secUrl}/init"
          log.info(s"initializating security at {}",initUrl)
          val attempt = wsclient.url(initUrl).withRequestTimeout(30.seconds).post(securityCredentials) flatMap { resp =>
            log.info("init response: {}",resp.toString)
            resp.status match {
              case 200 =>
                Try{resp.json.as[Seq[GestaltAPIKey]].head} match {
                  case Success(key) =>
                    Future.successful(SecurityInitializationComplete(key))
                  case Failure(e) =>
                    Future.failed(new RuntimeException("error extracting API key form security initialization response"))
                }
              case not200 =>
                val mesg = Try{(resp.json \ "message").as[String]}.getOrElse(resp.body)
                Future.failed(new RuntimeException(mesg))
            }
          }
          attempt.onComplete {
            case Success(initComplete) =>
              sendMessageToSelf(0.seconds, initComplete)
            case Failure(ex) =>
              log.warning("error initializing security service: {}",ex.getMessage)
              // keep retrying until our time runs out and we leave this state
              sendMessageToSelf(5.seconds, RetryRequest)
          }
      }
  }

  standardWhen(LaunchingDB)

  standardWhen(LaunchingRabbit)

  standardWhen(LaunchingMeta)

  when(LaunchingSecurity) {
    case Event(e @ MarathonStatusUpdateEvent(_, _, "TASK_RUNNING", _, appId, host, ports, _, _, _, _) , d) if appId == s"/${appGroup}/security" =>
      log.info("security running")
      sendMessageToSelf(5.minutes, StateTimeout)
      goto(stateName.next) using ServiceData(
        securityUrl = Some(s"${host}:${ports.headOption.getOrElse(9455)}"),
        adminKey = None,
        error = None
      )
  }

  when(WaitingForAPIKeys) {
    case Event(RetryRequest, d) =>
      goto(WaitingForAPIKeys)
    case Event(SecurityInitializationComplete(apiKey), d) =>
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
    case Event(ErrorEvent(message),d) =>
      goto(Error) using d.copy(
        error = Some(message)
      )
    case Event(StatusRequest,d) =>
      stay replying StatusResponse(launcherStage = stateName.toString, error = d.error)
  }

  onTransition {
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

  initialize()
}
