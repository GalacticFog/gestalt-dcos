package com.galacticfog.gestalt.dcos.marathon

import play.api.libs.json._               // JSON library
import play.api.libs.json.Reads._         // Custom validation helpers
import play.api.libs.functional.syntax._  // Combinator syntax

case class MarathonStatusUpdateEvent(slaveId: String,
                                     taskId: String,
                                     taskStatus: String,
                                     message: String,
                                     appId: String,
                                     workerHost: String,
                                     workerPorts: Seq[Int],
                                     version: String,
                                     timestamp: String)

object JSONImports {
  implicit val statusUpdateEventRead = Json.reads[MarathonStatusUpdateEvent]
}
