package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.marathon.MarathonDeploymentInfo.Step
import com.galacticfog.gestalt.dcos.marathon.MarathonDeploymentInfo.Step.Action
import play.api.libs.json.{Json, OFormat, Reads}

package object marathon {

  implicit val marResidencyFmt: OFormat[Residency] = Json.format[Residency]
  implicit val readinessCheckFmt: OFormat[MarathonReadinessCheck] = Json.format[MarathonReadinessCheck]
  implicit val dockerPortMappingFmt: OFormat[DockerPortMapping] = Json.format[DockerPortMapping]
  implicit val keyValuePairFmt: OFormat[KeyValuePair] = Json.format[KeyValuePair]
  implicit val volumePersistenceFmt: OFormat[VolumePersistence] = Json.format[VolumePersistence]
  implicit val volumeFmt: OFormat[Volume] = Json.format[marathon.Volume]
  implicit val portDefinitionFmt: OFormat[PortDefinition] = Json.format[PortDefinition]
  implicit val marDockerFmt: OFormat[MarathonDockerContainer] = Json.format[MarathonDockerContainer]
  implicit val healthCheckFmt: OFormat[MarathonHealthCheck] = Json.format[MarathonHealthCheck]
  implicit val discoverPortInfoFmt: OFormat[DiscoveryPortInfo] = Json.format[DiscoveryPortInfo]
  implicit val discoveryInfoFmt: OFormat[DiscoveryInfo] = Json.format[DiscoveryInfo]
  implicit val ipPerTaskInfoFmt: OFormat[MarathonAppPayload.IPPerTaskInfo] = Json.format[MarathonAppPayload.IPPerTaskInfo]
  implicit val ipAddressFmt: OFormat[IPAddress] = Json.format[IPAddress]
  implicit val marTaskFmt: OFormat[MarathonTask] = Json.format[MarathonTask]
  implicit val marContainerInfoFmt: OFormat[MarathonContainerInfo] = Json.format[MarathonContainerInfo]

  implicit val marAppPayloadReads: OFormat[MarathonAppPayload] = Json.format[MarathonAppPayload]

  implicit val statusUpdateEventRead: Reads[MarathonStatusUpdateEvent] = Json.reads[MarathonStatusUpdateEvent]
  implicit val deploymentSuccessRead: Reads[MarathonDeploymentSuccess] = Json.reads[MarathonDeploymentSuccess]
  implicit val deploymentFailureRead: Reads[MarathonDeploymentFailure] = Json.reads[MarathonDeploymentFailure]
  implicit val healthStatusChangedRead: Reads[MarathonHealthStatusChange] = Json.reads[MarathonHealthStatusChange]
  implicit val appTerminatedEventRead: Reads[MarathonAppTerminatedEvent] = Json.reads[MarathonAppTerminatedEvent]
  implicit val deploymentStepActions: Reads[Action] = Json.reads[MarathonDeploymentInfo.Step.Action]
  implicit val deploymentStep: Reads[Step] = Json.reads[MarathonDeploymentInfo.Step]
  implicit val deploymentInfo: Reads[MarathonDeploymentInfo] = Json.reads[MarathonDeploymentInfo]

}
