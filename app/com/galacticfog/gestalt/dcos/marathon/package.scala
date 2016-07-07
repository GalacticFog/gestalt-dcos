package com.galacticfog.gestalt.dcos

import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json

package object marathon {

  implicit val readinessCheckFmt = Json.format[MarathonReadinessCheck]
  implicit val dockerPortMappingFmt = Json.format[DockerPortMapping]
  implicit val keyValuePairFmt = Json.format[KeyValuePair]
  implicit val portDefinitionFmt = Json.format[PortDefinition]
  implicit val marDockerFmt = Json.format[MarathonDockerContainer]
  implicit val healthCheckFmt = Json.format[MarathonHealthCheck]
  implicit val discoverPortInfoFmt = Json.format[DiscoveryPortInfo]
  implicit val discoveryInfoFmt = Json.format[DiscoveryInfo]
  implicit val ipPerTaskInfoFmt = Json.format[IPPerTaskInfo]
  implicit val ipAddressFmt = Json.format[IPAddress]
  implicit val marTaskFmt = Json.format[MarathonTask]

  implicit val marContainerInfoReads: Reads[MarathonContainerInfo] = (
    (__ \ "type").read[String] and
      (__ \ "docker").readNullable[MarathonDockerContainer]
  )(MarathonContainerInfo.apply _)
  implicit val marContainerInfoWrites: Writes[MarathonContainerInfo] = (
    (__ \ "type").write[String] and
      (__ \ "docker").writeNullable[MarathonDockerContainer]
  )(unlift(MarathonContainerInfo.unapply))

  implicit val marAppPayloadFmt = Json.format[MarathonAppPayload]

}
