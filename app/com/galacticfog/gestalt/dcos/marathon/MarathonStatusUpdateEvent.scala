package com.galacticfog.gestalt.dcos.marathon

sealed trait MarathonEvent {
  def eventType: String
}

case class MarathonStatusUpdateEvent( slaveId: String,
                                      taskId: String,
                                      taskStatus: String,
                                      message: String,
                                      appId: String,
                                      host: String,
                                      ports: Seq[Int],
                                      ipAddresses: Option[Seq[IPAddress]],
                                      version: String,
                                      timestamp: String) extends MarathonEvent {
  val eventType = MarathonStatusUpdateEvent.eventType
}
case object MarathonStatusUpdateEvent {
  val eventType = "status_update_event"
}

case class MarathonDeploymentSuccess( timestamp: String,
                                      id: String) extends MarathonEvent {
  val eventType = MarathonDeploymentSuccess.eventType
}
case object MarathonDeploymentSuccess extends {
  val eventType = "deployment_success"
}

case class MarathonDeploymentFailure( timestamp: String,
                                      id: String) extends MarathonEvent {
  val eventType = MarathonDeploymentFailure.eventType
}
case object MarathonDeploymentFailure {
  val eventType = "deployment_failed"
}

case class MarathonHealthStatusChange( timestamp: String,
                                       appId: String,
                                       taskId: Option[String],
                                       instanceId: Option[String],
                                       version: String,
                                       alive: Boolean) extends MarathonEvent {
  val eventType = MarathonHealthStatusChange.eventType
}
case object MarathonHealthStatusChange {
  val eventType = "health_status_changed_event"
}

case class MarathonAppTerminatedEvent( appId: String,
                                       timestamp: String ) extends MarathonEvent {
  val eventType = MarathonAppTerminatedEvent.eventType
}
case object MarathonAppTerminatedEvent {
  val eventType = "app_terminated_event"
}

case class MarathonDeploymentInfo( currentStep: MarathonDeploymentInfo.Step,
                                   eventType: String,
                                   timestamp: String) extends MarathonEvent
case object MarathonDeploymentInfo {
  val eventType = "deployment_info"

  case class Step(actions: Seq[Step.Action])

  case object Step {
    case class Action(action: String, app: String)
  }
}
