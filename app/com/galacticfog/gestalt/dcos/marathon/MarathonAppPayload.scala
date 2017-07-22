package com.galacticfog.gestalt.dcos.marathon

import play.api.libs.json.JsObject

case class KeyValuePair(key: Option[String], value: Option[String])

case class VolumePersistence(size: Option[Int])

case class Volume(containerPath: Option[String],
                  mode: Option[String],
                  persistent: Option[VolumePersistence])

case class PortDefinition(port: Option[Int],
                          name: Option[String],
                          protocol: Option[String] = Some("tcp"),
                          labels: Option[Map[String,String]])

case class MarathonContainerInfo(`type`: Option[String] = Some(MarathonContainerInfo.Types.DOCKER),
                                 volumes: Option[Seq[Volume]] = None,
                                 docker: Option[MarathonDockerContainer] = None)

case object MarathonContainerInfo {
  object Types {
    val DOCKER = "DOCKER"
  }
}

case class DockerPortMapping(containerPort: Option[Int],
                             hostPort: Option[Int] = None,
                             servicePort: Option[Int] = None,
                             name: Option[String] = None,
                             protocol: Option[String] = Some("tcp"),
                             labels: Option[Map[String,String]] = None)

case class MarathonDockerContainer(image: Option[String],
                                   network: Option[String],
                                   privileged: Option[Boolean] = None,
                                   parameters: Option[Seq[KeyValuePair]] = None,
                                   forcePullImage: Option[Boolean] = None,
                                   portMappings: Option[Seq[DockerPortMapping]] = None)

case class MarathonHealthCheck(path: Option[String] = None,
                               protocol: Option[String] = None,
                               port: Option[Int] = None,
                               portIndex: Option[Int] = None,
                               gracePeriodSeconds: Option[Int] = None,
                               intervalSeconds: Option[Int] = None,
                               timeoutSeconds: Option[Int] = None,
                               maxConsecutiveFailures: Option[Int] = None)

case class MarathonReadinessCheck(protocol: Option[String] = Some("HTTP"),
                                  path: Option[String] = None,
                                  portName: Option[String],
                                  intervalSeconds: Option[Int] = None,
                                  timeoutSeconds: Option[Int] = None,
                                  httpStatusCodesForReady: Option[Seq[Int]] = Some(Seq(200)),
                                  preserveLastResponse: Option[Boolean] = None)


case class DiscoveryPortInfo(number: Option[Int],
                             name: Option[String],
                             protocol: Option[String],
                             labels: Option[Map[String,String]])

case class DiscoveryInfo(ports: Option[Seq[DiscoveryPortInfo]])

case class IPAddress(ipAddress: Option[String], protocol: Option[String])

case class MarathonTask(id: Option[String],
                        slaveId: Option[String],
                        host: Option[String],
                        startedAt: Option[String],
                        stagedAt: Option[String],
                        ports: Option[Seq[Int]],
                        version: Option[String],
                        ipAddresses: Option[Seq[IPAddress]],
                        appId: Option[String])

case class Residency(taskLostBehavior: Option[String], relaunchEscalationTimeoutSeconds: Option[Int] = Some(3600))

case object Residency {
  val WAIT_FOREVER: String = "WAIT_FOREVER"
}

case class MarathonAppPayload(id: Option[String],
                              cmd: Option[String] = None,
                              args: Option[Seq[String]] = None,
                              env: Option[JsObject] = None,
                              instances: Option[Int] = None,
                              cpus: Option[Double] = None,
                              mem: Option[Int] = None,
                              disk: Option[Int] = None,
                              container: Option[MarathonContainerInfo] = None,
                              portDefinitions: Option[Seq[PortDefinition]] = None,
                              requirePorts: Option[Boolean] = None,
                              healthChecks: Option[Seq[MarathonHealthCheck]] = None,
                              labels: Option[Map[String,String]] = None,
                              readinessCheck: Option[MarathonReadinessCheck] = None,
                              residency: Option[Residency] = None,
                              tasksStaged: Option[Int] = None,
                              tasksRunning: Option[Int] = None,
                              tasksHealthy: Option[Int] = None,
                              tasksUnhealthy: Option[Int] = None,
                              ipAddress: Option[MarathonAppPayload.IPPerTaskInfo] = None,
                              tasks: Option[Seq[MarathonTask]] = None,
                              taskKillGracePeriodSeconds: Option[Int] = None)

case object MarathonAppPayload {
  case class IPPerTaskInfo( discovery: Option[DiscoveryInfo] = None,
                            networkName: Option[String] = None )
}
