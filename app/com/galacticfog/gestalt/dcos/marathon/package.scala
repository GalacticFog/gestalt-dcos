package com.galacticfog.gestalt.dcos

import play.api.libs.json.Json

package object marathon {

  implicit val marResidencyFmt = Json.format[Residency]
  implicit val readinessCheckFmt = Json.format[MarathonReadinessCheck]
  implicit val dockerPortMappingFmt = Json.format[DockerPortMapping]
  implicit val keyValuePairFmt = Json.format[KeyValuePair]
  implicit val volumePersistenceFmt = Json.format[VolumePersistence]
  implicit val volumeFmt = Json.format[marathon.Volume]
  implicit val portDefinitionFmt = Json.format[PortDefinition]
  implicit val marDockerFmt = Json.format[MarathonDockerContainer]
  implicit val healthCheckFmt = Json.format[MarathonHealthCheck]
  implicit val discoverPortInfoFmt = Json.format[DiscoveryPortInfo]
  implicit val discoveryInfoFmt = Json.format[DiscoveryInfo]
  implicit val ipPerTaskInfoFmt = Json.format[IPPerTaskInfo]
  implicit val ipAddressFmt = Json.format[IPAddress]
  implicit val marTaskFmt = Json.format[MarathonTask]
  implicit val marContainerInfoFmt = Json.format[MarathonContainerInfo]

  implicit val marAppPayloadReads = Json.format[MarathonAppPayload]

  implicit val statusUpdateEventRead = Json.reads[MarathonStatusUpdateEvent]
  implicit val deploymentSuccessRead = Json.reads[MarathonDeploymentSuccess]
  implicit val deploymentFailureRead = Json.reads[MarathonDeploymentFailure]
  implicit val healthStatusChangedRead = Json.reads[MarathonHealthStatusChange]
  implicit val appTerminatedEventRead = Json.reads[MarathonAppTerminatedEvent]
  implicit val deploymentStepActions = Json.reads[MarathonDeploymentInfo.Step.Action]
  implicit val deploymentStep = Json.reads[MarathonDeploymentInfo.Step]
  implicit val deploymentInfo = Json.reads[MarathonDeploymentInfo]

}
