package com.galacticfog.gestalt.dcos.marathon

sealed trait MarathonEvent

case class MarathonStatusUpdateEvent( slaveId: String,
                                      taskId: String,
                                      taskStatus: String,
                                      message: String,
                                      appId: String,
                                      host: String,
                                      ports: Seq[Int],
                                      ipAddresses: Option[Seq[IPAddress]],
                                      eventType: String,
                                      version: String,
                                      timestamp: String) extends MarathonEvent

case class MarathonDeploymentSuccess( eventType: String,
                                      timestamp: String,
                                      id: String) extends MarathonEvent

case class MarathonDeploymentFailure( eventType: String,
                                      timestamp: String,
                                      id: String) extends MarathonEvent

case class MarathonHealthStatusChange( eventType: String,
                                       timestamp: String,
                                       appId: String,
                                       taskId: String,
                                       version: String,
                                       alive: Boolean) extends MarathonEvent

case class MarathonAppTerminatedEvent( appId: String,
                                       eventType: String,
                                       timestamp: String) extends MarathonEvent

case class MarathonDeploymentInfo( currentStep: MarathonDeploymentInfo.Step,
                                   eventType: String,
                                   timestamp: String) extends MarathonEvent

case object MarathonDeploymentInfo {
  case class Step(actions: Seq[Step.Action])

  case object Step {
    case class Action(action: String, app: String)
  }
}
