package com.galacticfog.gestalt.dcos

import scala.language.implicitConversions
import com.galacticfog.gestalt.dcos.LauncherConfig.{FrameworkService, ServiceEndpoint}
import com.galacticfog.gestalt.dcos.marathon._
import javax.inject.{Inject, Singleton}

import com.galacticfog.gestalt.dcos.HealthCheck.{MARATHON_HTTP, MARATHON_TCP}
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.Logger

case class PortSpec(number: Int, name: String, labels: Map[String,String], hostPort: Option[Int] = None)
case class HealthCheck(portIndex: Int, protocol: HealthCheck.HealthCheckProtocol, path: Option[String])
case object HealthCheck {
  sealed trait HealthCheckProtocol
  case object MARATHON_TCP   extends HealthCheckProtocol {override def toString: String = "TCP"}
  case object MARATHON_HTTP  extends HealthCheckProtocol {override def toString: String = "HTTP"}
  case object MARATHON_HTTPS extends HealthCheckProtocol {override def toString: String = "HTTPS"}
  // case object MESOS_TCP      extends HealthCheckProtocol {override def toString: String = "MESOS_TCP"}
  // case object MESOS_HTTP     extends HealthCheckProtocol {override def toString: String = "MESOS_HTTP"}
  // case object MESOS_HTTPS    extends HealthCheckProtocol {override def toString: String = "MESOS_HTTPS"}
  case object COMMAND        extends HealthCheckProtocol {override def toString: String = "COMMAND"}
}

case class AppSpec(name: String,
                   image: String,
                   numInstances: Int = 1,
                   network: Protos.ContainerInfo.DockerInfo.Network,
                   ports: Option[Seq[PortSpec]] = None,
                   dockerParameters: Seq[KeyValuePair] = Seq.empty,
                   cpus: Double,
                   mem: Int,
                   env: Map[String,String] = Map.empty,
                   labels: Map[String,String ] = Map.empty,
                   args: Option[Seq[String]] = None,
                   cmd: Option[String] = None,
                   volumes: Option[Seq[marathon.Volume]] = None,
                   residency: Option[Residency] = None,
                   healthChecks: Seq[HealthCheck] = Seq.empty,
                   taskKillGracePeriodSeconds: Option[Int] = None,
                   readinessCheck: Option[MarathonReadinessCheck] = None)

@Singleton
class GestaltTaskFactory @Inject() ( launcherConfig: LauncherConfig ) {

  val RABBIT_POLICY_EXCHANGE = "policy-exchange"
  val RABBIT_POLICY_ROUTE    = "policy"

  Logger.info("gestalt-framework-version: " + launcherConfig.gestaltFrameworkVersion)

  import LauncherConfig.Services._

  def appSpec(service: FrameworkService) = AppSpec(
    name = service.name,
    args = Some(Seq()),
    image = launcherConfig.dockerImage(service),
    cpus = service.cpu,
    mem = service.mem,
    network = ContainerInfo.DockerInfo.Network.BRIDGE
  )

  def getVhostLabels(service: FrameworkService): Map[String,String] = {
    val vhosts = launcherConfig.marathon.tld match {
      case Some(tld) => service match {
        case UI => Map(
          "HAPROXY_0_VHOST" -> tld
        )
        case SECURITY | META => Map(
          "HAPROXY_0_VHOST" -> s"${service.name}.$tld",
          "HAPROXY_1_VHOST" -> s"$tld",
          "HAPROXY_1_PATH"  -> s"/${service.name}",
          "HAPROXY_1_HTTP_BACKEND_PROXYPASS_PATH" -> s"/${service.name}"
        )
        case _ => Map.empty
      }
      case None => Map.empty
    }
    Map("HAPROXY_GROUP" -> "external") ++ vhosts
  }

  def serviceHostname: (ServiceEndpoint) => String = launcherConfig.vipHostname(_)

  def vipDestination(service: ServiceEndpoint) = s"${serviceHostname(service)}:${service.port}"

  def vipLabel: (ServiceEndpoint) => String = launcherConfig.vipLabel(_)

  def vipPort(service: ServiceEndpoint): String = service.port.toString

  def getAppSpec(service: FrameworkService, globalConfig: GlobalConfig): AppSpec = {
    service match {
      case DATA(index) => getData(globalConfig.dbConfig.get, index)
      case RABBIT      => getRabbit
      case SECURITY    => getSecurity(globalConfig.dbConfig.get)
      case META        => getMeta(globalConfig.dbConfig.get, globalConfig.secConfig.get)
      case UI          => getUI
    }
  }

  private[this] def gestaltSecurityEnvVars(secConfig: GlobalSecConfig): Map[String, String] = Map(
    "GESTALT_SECURITY_PROTOCOL" -> "http",
    "GESTALT_SECURITY_HOSTNAME" -> secConfig.hostname,
    "GESTALT_SECURITY_PORT"     -> secConfig.port.toString,
    "GESTALT_SECURITY_KEY"      -> secConfig.apiKey,
    "GESTALT_SECURITY_SECRET"   -> secConfig.apiSecret,
    "GESTALT_SECURITY_REALM"    -> secConfig.realm
  )

  private[this] def getData(dbConfig: GlobalDBConfig, index: Int): AppSpec = {
    val replEnv = if (index > 0) Map(
      "PGREPL_MASTER_IP" -> serviceHostname(DATA(0)),
      "PGREPL_MASTER_PORT" -> vipPort(DATA(0))
    ) else Map.empty
    appSpec(DATA(index)).copy(
      volumes = Some(Seq(marathon.Volume(
        containerPath = "pgdata",
        mode = "RW",
        persistent = Some(VolumePersistence(
          size = launcherConfig.database.provisionedSize
        ))
      ))),
      residency = Some(Residency(Residency.WAIT_FOREVER)),
      taskKillGracePeriodSeconds = Some(LauncherConfig.DatabaseConfig.DEFAULT_KILL_GRACE_PERIOD),
      env = replEnv ++ Map(
        "POSTGRES_USER" -> dbConfig.username,
        "POSTGRES_PASSWORD" -> dbConfig.password,
        "PGDATA" -> "/mnt/mesos/sandbox/pgdata",
        "PGREPL_ROLE" -> (if (index == 0) "PRIMARY" else "STANDBY"),
        "PGREPL_TOKEN" -> launcherConfig.database.pgreplToken
      ),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 5432, name = "sql", labels = Map("VIP_0" -> vipLabel(DATA(index))), hostPort = if (index == 0) Some(5432) else None))),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = MARATHON_TCP, path = None
      ))
    )
  }

  private[this] def getRabbit: AppSpec = {
    appSpec(RABBIT).copy(
      ports = Some(Seq(
        PortSpec(number = 5672,  name = "service-api", labels = Map("VIP_0" -> vipLabel(RABBIT_AMQP)), hostPort = Some(5672)),
        PortSpec(number = 15672, name = "http-api",    labels = Map("VIP_0" -> vipLabel(RABBIT_HTTP)), hostPort = Some(15672))
      )),
      healthChecks = Seq(HealthCheck(
        portIndex = 1, protocol = MARATHON_HTTP, path = Some("/")
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200),
        intervalSeconds = 5,
        timeoutSeconds = 10
      ))
    )
  }

  private[this] def getSecurity(dbConfig: GlobalDBConfig): AppSpec = {
    appSpec(SECURITY).copy(
      args = Some(Seq(s"-J-Xmx${(SECURITY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = Map(
        "DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "DATABASE_PORT"     -> s"${dbConfig.port}",
        "DATABASE_NAME"     -> s"${dbConfig.prefix}security",
        "DATABASE_USERNAME" -> s"${dbConfig.username}",
        "DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
        "OAUTH_RATE_LIMITING_PERIOD" -> "1"
      ),
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api",      labels = Map("VIP_0" -> vipLabel(SECURITY))),
        PortSpec(number = 9000, name = "http-api-dupe", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = MARATHON_HTTP, path = Some("/health")
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/init",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = getVhostLabels(SECURITY)
    )
  }

  private[this] def getMeta(dbConfig: GlobalDBConfig, secConfig: GlobalSecConfig): AppSpec = {
    appSpec(META).copy(
      args = Some(Seq(s"-J-Xmx${(META.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = gestaltSecurityEnvVars(secConfig) ++ Map(
        "META_POLICY_CALLBACK_URL" -> s"http://${vipDestination(META)}",
        //
        "DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "DATABASE_PORT"     -> s"${dbConfig.port}",
        "DATABASE_NAME"     -> s"${dbConfig.prefix}meta",
        "DATABASE_USERNAME" -> s"${dbConfig.username}",
        "DATABASE_PASSWORD" -> s"${dbConfig.password}",
        //
        "RABBIT_HOST"      -> serviceHostname(RABBIT_AMQP),
        "RABBIT_PORT"      -> vipPort(RABBIT_AMQP),
        "RABBIT_HTTP_PORT" -> vipPort(RABBIT_HTTP),
        "RABBIT_EXCHANGE"  -> RABBIT_POLICY_EXCHANGE,
        "RABBIT_ROUTE"     -> RABBIT_POLICY_ROUTE
      ),
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(META))),
        PortSpec(number = 9000, name = "http-api-dupe", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(portIndex = 0, protocol = MARATHON_HTTP, path = Some("/health"))),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/health",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200,401,403),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = getVhostLabels(META)
    )
  }

  private[this] def getUI: AppSpec = {
    appSpec(UI).copy(
      env = Map(
        "META_API_URL" -> s"http://${vipDestination(META)}",
        "SEC_API_URL"  -> s"http://${vipDestination(SECURITY)}"
      ),
      ports = Some(Seq(
        PortSpec(number = 80, name = "http", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(
        path = Some("/#/login"),
        protocol = MARATHON_HTTP,
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/#/login",
        portName = "http",
        intervalSeconds = 5
      )),
      labels = getVhostLabels(UI)
    )
  }

  implicit private[this] def getVariables(env: Map[String,String]): Environment = {
    val builder = Environment.newBuilder()
    env.foreach {
      case (name,value) => builder.addVariables(Variable.newBuilder
        .setName(name)
        .setValue(value)
      )
    }
    builder.build
  }

  def getMarathonPayload( service: FrameworkService,
                          globalConfig: GlobalConfig ): MarathonAppPayload = toMarathonPayload(getAppSpec(service, globalConfig))

  def toMarathonPayload(app: AppSpec): MarathonAppPayload = {
    val isBridged = app.network.getValueDescriptor.getName == "BRIDGE"
    MarathonAppPayload(
      id = "/" + launcherConfig.marathon.appGroup + "/" + app.name,
      args = app.args,
      env = app.env,
      instances = app.numInstances,
      cpus = app.cpus,
      cmd = app.cmd,
      mem = app.mem,
      disk = 0,
      requirePorts = true,
      residency = app.residency,
      container = MarathonContainerInfo(
        `type` = "DOCKER",
        volumes = app.volumes,
        docker = Some(MarathonDockerContainer(
          image = app.image,
          network = app.network.getValueDescriptor.getName,
          privileged = false,
          parameters = Seq(),
          forcePullImage = true,
          portMappings = if (isBridged) app.ports.map {_.map(
            p => DockerPortMapping(containerPort = p.number, name = Some(p.name), protocol = "tcp", labels = Some(p.labels), hostPort = p.hostPort)
          ) } else None
        ))
      ),
      labels = app.labels,
      healthChecks = app.healthChecks.map( hc => MarathonHealthCheck(
        path = hc.path,
        protocol = hc.protocol.toString,
        portIndex = hc.portIndex,
        gracePeriodSeconds = 300,
        intervalSeconds = 30,
        timeoutSeconds = 15,
        maxConsecutiveFailures = 4
      ) ),
      readinessCheck = app.readinessCheck,
      portDefinitions = if (!isBridged) app.ports.map {_.map(
        p => PortDefinition(port = p.number, protocol = "tcp", name = Some(p.name), labels = Some(p.labels))
      )} else None,
      taskKillGracePeriodSeconds = app.taskKillGracePeriodSeconds
    )
  }

}
