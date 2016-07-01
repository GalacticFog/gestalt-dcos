package com.galacticfog.gestalt.dcos.marathon

import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.joda.time.DateTime

case class KeyValuePair(key: String, value: String)

case class PortDefinition(port: Int,
                          protocol: String,
                          labels: Option[Map[String,String]])

case class MarathonContainerInfo(containerType: String,
                                 docker: Option[MarathonDockerContainer])

case class MarathonDockerContainer(image: String,
                                   network: String,
                                   privileged: Boolean,
                                   parameters: Seq[KeyValuePair],
                                   forcePullImage: Boolean)

case class HealthCheck(path: String,
                       protocol: String,
                       portIndex: Int,
                       gracePeriodSeconds: Int,
                       intervalSeconds: Int,
                       timeoutSeconds: Int,
                       maxConsecutiveFailures: Int)

case class DiscoveryPortInfo(number: Int,
                             name: Option[String],
                             protocol: Option[String],
                             labels: Option[Map[String,String]])

case class DiscoveryInfo(ports: Option[Seq[DiscoveryPortInfo]])

case class IPPerTaskInfo(discovery: Option[DiscoveryInfo])

case class IPAddress(ipAddress: String, protocol: String)

case class MarathonTask(id: String,
                        slaveId: String,
                        host: String,
                        startedAt: DateTime,
                        stagedAt: DateTime,
                        ports: Seq[Int],
                        version: String,
                        ipAddresses: Seq[IPAddress],
                        appId: String)

case class MarathonAppPayload(id: String,
                              cmd: Option[String] = None,
                              args: Option[Seq[String]] = None,
                              user: Option[String] = None,
                              env: Map[String,String],
                              instances: Int,
                              cpus: Double,
                              mem: Int,
                              disk: Int,
                              portDefinitions: Option[Seq[PortDefinition]] = None,
                              requirePorts: Boolean,
                              healthChecks: Seq[HealthCheck],
                              labels: Map[String,String],
                              acceptedResourceRoles: Option[String] = None,
                              ipAddress: Option[IPPerTaskInfo] = None,
                              tasksStaged: Option[Int] = None,
                              tasksRunning: Option[Int] = None,
                              tasksHealthy: Option[Int] = None,
                              tasksUnhealthy: Option[Int] = None,
                              tasks: Option[Seq[MarathonTask]] = None)

object JSONImports {
  implicit val keyValuePairFmt = Json.format[KeyValuePair]
  implicit val portDefinitionFmt = Json.format[PortDefinition]
  implicit val marDockerFmt = Json.format[MarathonDockerContainer]
  implicit val marContainerInfoFmt = Json.format[MarathonContainerInfo]
  implicit val healthCheckFmt = Json.format[HealthCheck]
  implicit val discoverPortInfoFmt = Json.format[DiscoveryPortInfo]
  implicit val discoveryInfoFmt = Json.format[DiscoveryInfo]
  implicit val ipPerTaskInfoFmt = Json.format[IPPerTaskInfo]
  implicit val ipAddressFmt = Json.format[IPAddress]
  implicit val marTaskFmt = Json.format[MarathonTask]
  implicit val marAppPayloadFmt = Json.format[MarathonAppPayload]
}
