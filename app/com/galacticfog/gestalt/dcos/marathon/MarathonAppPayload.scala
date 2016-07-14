package com.galacticfog.gestalt.dcos.marathon

case class KeyValuePair(key: String, value: String)

case class VolumePersistence(size: Int)

case class Volume(containerPath: String,
                  mode: String,
                  persistent: VolumePersistence)

case class PortDefinition(port: Int,
                          name: Option[String],
                          protocol: String,
                          labels: Option[Map[String,String]])

case class MarathonContainerInfo(`type`: String,
                                 volumes: Option[Seq[Volume]],
                                 docker: Option[MarathonDockerContainer])

case class DockerPortMapping(containerPort: Int,
                             hostPort: Option[Int] = None,
                             servicePort: Option[Int] = None,
                             name: Option[String] = None,
                             protocol: String,
                             labels: Option[Map[String,String]] = None)

case class MarathonDockerContainer(image: String,
                                   network: String,
                                   privileged: Boolean,
                                   parameters: Seq[KeyValuePair],
                                   forcePullImage: Boolean,
                                   portMappings: Option[Seq[DockerPortMapping]])

case class MarathonHealthCheck(path: String,
                               protocol: String,
                               portIndex: Int,
                               gracePeriodSeconds: Int,
                               intervalSeconds: Int,
                               timeoutSeconds: Int,
                               maxConsecutiveFailures: Int)

case class MarathonReadinessCheck(protocol: String = "HTTP",
                                  path: String = "",
                                  portName: String,
                                  intervalSeconds: Int = 30,
                                  timeoutSeconds: Int = 10,
                                  httpStatusCodesForReady: Seq[Int] = Seq(200),
                                  preserveLastResponse: Boolean = false)


case class DiscoveryPortInfo(number: Int,
                             name: Option[String],
                             protocol: Option[String],
                             labels: Option[Map[String,String]])

case class DiscoveryInfo(ports: Option[Seq[DiscoveryPortInfo]])

case class IPPerTaskInfo(discovery: Option[DiscoveryInfo])

case class IPAddress(ipAddress: String, protocol: String)

case class MarathonTask(id: Option[String],
                        slaveId: Option[String],
                        host: Option[String],
                        startedAt: Option[String],
                        stagedAt: Option[String],
                        ports: Option[Seq[Int]],
                        version: Option[String],
                        ipAddresses: Option[Seq[IPAddress]],
                        appId: Option[String])

case class MarathonAppPayload(id: String,
                              cmd: Option[String] = None,
                              args: Option[Seq[String]] = None,
                              user: Option[String] = None,
                              env: Map[String,String],
                              instances: Int,
                              cpus: Double,
                              mem: Int,
                              disk: Int,
                              container: MarathonContainerInfo,
                              portDefinitions: Option[Seq[PortDefinition]] = None,
                              requirePorts: Boolean,
                              healthChecks: Seq[MarathonHealthCheck],
                              labels: Map[String,String],
                              acceptedResourceRoles: Option[String] = None,
                              readinessCheck: Option[MarathonReadinessCheck] = None,
                              ipAddress: Option[IPPerTaskInfo] = None,
                              tasksStaged: Option[Int] = None,
                              tasksRunning: Option[Int] = None,
                              tasksHealthy: Option[Int] = None,
                              tasksUnhealthy: Option[Int] = None,
                              tasks: Option[Seq[MarathonTask]] = None)

