package com.galacticfog.gestalt.dcos.marathon

import play.api.libs.json._               // JSON library
import play.api.libs.json.Reads._         // Custom validation helpers
import play.api.libs.functional.syntax._  // Combinator syntax

case class MarathonStatusUpdateEvent(slaveId: String,
                                     taskId: String,
                                     taskStatus: String,
                                     message: String,
                                     appId: String,
                                     host: String,
                                     ports: Seq[Int],
                                     ipAddresses: Seq[IPAddress],
                                     eventType: String,
                                     version: String,
                                     timestamp: String)

case class MarathonDeploymentSuccess(eventType: String,
                                     timestamp: String,
                                     id: String)

case class MarathonDeploymentFailure(eventType: String,
                                     timestamp: String,
                                     id: String)

case class MarathonHealthStatusChange(eventType: String,
                                      timestamp: String,
                                      appId: String,
                                      taskId: String,
                                      version: String,
                                      alive: Boolean)

object JSONImports {
  implicit val statusUpdateEventRead = Json.reads[MarathonStatusUpdateEvent]
  implicit val deploymentSuccess = Json.reads[MarathonDeploymentSuccess]
  implicit val deploymentFailure = Json.reads[MarathonDeploymentFailure]
  implicit val healthStatusChanged = Json.reads[MarathonHealthStatusChange]
}

