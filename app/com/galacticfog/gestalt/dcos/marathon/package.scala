package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.marathon.MarathonDeploymentInfo.Step
import com.galacticfog.gestalt.dcos.marathon.MarathonDeploymentInfo.Step.Action
import play.api.libs.json.{Json, OFormat, Reads}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

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

  val marAppPayloadFmt1: OFormat[(
    Option[String],
      Option[String],
      Option[Seq[String]],
      Option[JsObject],
      Option[Int],
      Option[Double],
      Option[Int],
      Option[Int],
      Option[MarathonContainerInfo],
      Option[Seq[PortDefinition]],
      Option[Boolean],
      Option[Seq[MarathonHealthCheck]],
      Option[Map[String,String]],
      Option[MarathonReadinessCheck],
      Option[Residency],
      Option[Int],
      Option[Int],
      Option[Int],
      Option[Int],
      Option[MarathonAppPayload.IPPerTaskInfo],
      Option[Seq[MarathonTask]]
    )] = (
    (__ \ "id").formatNullable[String] ~
      (__ \ "cmd").formatNullable[String] ~
      (__ \ "args").formatNullable[Seq[String]] ~
      (__ \ "env").formatNullable[JsObject] ~
      (__ \ "instances").formatNullable[Int] ~
      (__ \ "cpus").formatNullable[Double] ~
      (__ \ "mem").formatNullable[Int] ~
      (__ \ "disk").formatNullable[Int] ~
      (__ \ "container").formatNullable[MarathonContainerInfo] ~
      (__ \ "portDefinitions").formatNullable[Seq[PortDefinition]] ~
      (__ \ "requirePorts").formatNullable[Boolean] ~
      (__ \ "healthChecks").formatNullable[Seq[MarathonHealthCheck]] ~
      (__ \ "labels").formatNullable[Map[String,String]] ~
      (__ \ "readinessCheck").formatNullable[MarathonReadinessCheck] ~
      (__ \ "residency").formatNullable[Residency] ~
      (__ \ "tasksStaged").formatNullable[Int] ~
      (__ \ "tasksRunning").formatNullable[Int] ~
      (__ \ "tasksHealthy").formatNullable[Int] ~
      (__ \ "tasksUnhealthy").formatNullable[Int] ~
      (__ \ "ipAddress").formatNullable[MarathonAppPayload.IPPerTaskInfo] ~
      (__ \ "tasks").formatNullable[Seq[MarathonTask]]
    ).tupled

  val marAppPayloadFmt2: OFormat[(
    Option[Int],
      Option[Seq[JsObject]]
  )] = (
    (__ \ "taskKillGracePeriodSeconds").formatNullable[Int] ~
      (__ \ "networks").formatNullable[Seq[JsObject]]
  ).tupled

  implicit val marAppPayloadFmt: OFormat[MarathonAppPayload] = (marAppPayloadFmt1 ~ marAppPayloadFmt2)({
    case (
      (id,
      cmd,
      args,
      env,
      instances,
      cpus,
      mem,
      disk,
      container,
      portDefinitions,
      requirePorts,
      healthChecks,
      labels,
      readinessCheck,
      residency,
      tasksStaged,
      tasksRunning,
      tasksHealthy,
      tasksUnhealthy,
      ipAddress,
      tasks),
      (taskKillGracePeriodSeconds,
      networks)) => MarathonAppPayload(id,
      cmd,
      args,
      env,
      instances,
      cpus,
      mem,
      disk,
      container,
      portDefinitions,
      requirePorts,
      healthChecks,
      labels,
      readinessCheck,
      residency,
      tasksStaged,
      tasksRunning,
      tasksHealthy,
      tasksUnhealthy,
      ipAddress,
      tasks,
      taskKillGracePeriodSeconds,
      networks)
  }, (p: MarathonAppPayload) =>
    ((p.id,
      p.cmd,
      p.args,
      p.env,
      p.instances,
      p.cpus,
      p.mem,
      p.disk,
      p.container,
      p.portDefinitions,
      p.requirePorts,
      p.healthChecks,
      p.labels,
      p.readinessCheck,
      p.residency,
      p.tasksStaged,
      p.tasksRunning,
      p.tasksHealthy,
      p.tasksUnhealthy,
      p.ipAddress,
      p.tasks),
    (p.taskKillGracePeriodSeconds,
      p.networks))
  )

  implicit val statusUpdateEventRead: Reads[MarathonStatusUpdateEvent] = Json.reads[MarathonStatusUpdateEvent]
  implicit val deploymentSuccessRead: Reads[MarathonDeploymentSuccess] = Json.reads[MarathonDeploymentSuccess]
  implicit val deploymentFailureRead: Reads[MarathonDeploymentFailure] = Json.reads[MarathonDeploymentFailure]
  implicit val healthStatusChangedRead: Reads[MarathonHealthStatusChange] = Json.reads[MarathonHealthStatusChange]
  implicit val appTerminatedEventRead: Reads[MarathonAppTerminatedEvent] = Json.reads[MarathonAppTerminatedEvent]
  implicit val deploymentStepActions: Reads[Action] = Json.reads[MarathonDeploymentInfo.Step.Action]
  implicit val deploymentStep: Reads[Step] = Json.reads[MarathonDeploymentInfo.Step]
  implicit val deploymentInfo: Reads[MarathonDeploymentInfo] = Json.reads[MarathonDeploymentInfo]

}
