package com.galacticfog.gestalt.dcos.marathon

import akka.actor.LoggingFSM
import com.galacticfog.gestalt.security.api.{GestaltAPIKey, GestaltAPICredentials}
import de.heikoseeberger.akkasse.ServerSentEvent

sealed trait LauncherState
case object Uninitialized extends LauncherState
case object LaunchingDB   extends LauncherState
case object LaunchingSecurity extends LauncherState
case object WaitingForAPIKeys extends LauncherState

final case class ServiceData(adminKey: Option[GestaltAPIKey])

case object ServiceStatusRequest
case object LaunchServicesRequest
case object ShutdownRequest
final case class ServiceStatusResponse(states: Map[String,String])

class GestaltMarathonLauncher extends LoggingFSM[LauncherState,ServiceData] {

  startWith(Uninitialized, ServiceData(None))

  when(Uninitialized) {
    case Event(LaunchServicesRequest,d) =>
      goto(LaunchingDB)
  }

  onTransition {
    case Uninitialized -> LaunchingDB =>
      log.info("launching database")
  }

  when(LaunchingDB) {
    case Event(e @ MarathonStatusUpdateEvent(_, "/gestalt/data", "TASK_RUNNING", _, _, _,_, _, _) , d) =>
      log.info("database running")
      goto(LaunchingSecurity)
  }

  onTransition {
    case LaunchingDB -> LaunchingSecurity =>
      log.info("launching security")
  }

  when(LaunchingSecurity) {
    case Event(e @ MarathonStatusUpdateEvent(_, "/gestalt/security", "TASK_RUNNING", _, _, _,_, _, _) , d) =>
      log.info("security running")
      goto(WaitingForAPIKeys)
  }

  whenUnhandled {
    case Event(ShutdownRequest,d) =>
      goto(Uninitialized)
    case Event(ServiceStatusRequest,d) =>
      sender ! ServiceStatusResponse(Map.empty)
      stay
  }

  onTransition {
    case s -> Uninitialized if s != Uninitialized =>
      log.info("shut it down")
  }

  onTransition {
    case x -> y =>
      log.info("transitioned " + x + " -> " + y)
  }

  initialize()

}
