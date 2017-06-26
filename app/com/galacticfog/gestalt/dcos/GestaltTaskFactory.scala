package com.galacticfog.gestalt.dcos

import java.util.UUID

import scala.language.implicitConversions
import com.galacticfog.gestalt.dcos.LauncherConfig.{FrameworkService, LaserConfig, ServiceEndpoint}
import com.galacticfog.gestalt.dcos.marathon._
import javax.inject.{Inject, Singleton}

import com.galacticfog.gestalt.cli.GestaltProviderBuilder.CaaSTypes
import com.galacticfog.gestalt.cli._
import com.galacticfog.gestalt.dcos.HealthCheck.{MARATHON_HTTP, MARATHON_TCP}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.Logger
import play.api.libs.json.JsValue

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
    cpus = launcherConfig.debug.cpu.getOrElse(service.cpu),
    mem = launcherConfig.debug.mem.getOrElse(service.mem),
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
    "GESTALT_SECURITY_SECRET"   -> secConfig.apiSecret
  ) ++ secConfig.realm.map("GESTALT_SECURITY_REALM" -> _)


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

  def getCaasProvider(): JsValue = {
    GestaltProviderBuilder.caasProvider(CaaSSecrets(
      url = Some(launcherConfig.marathon.baseUrl),
      username = Some("unused"),
      password = Some("unused"),
      kubeconfig = None,
      appGroupPrefix = Some(launcherConfig.marathon.appGroup)
    ), GestaltProviderBuilder.CaaSTypes.DCOS)
  }

  def getDbProvider(dbConfig: GlobalDBConfig): JsValue = {
    GestaltProviderBuilder.dbProvider(DBSecrets(
      username = dbConfig.username,
      password = dbConfig.password,
      host     = dbConfig.hostname,
      port     = dbConfig.port,
      protocol = "tcp"
    ))
  }

  def getRabbitProvider(): JsValue = {
    GestaltProviderBuilder.rabbitProvider(RabbitSecrets(
      host = launcherConfig.vipHostname(RABBIT_AMQP),
      port = RABBIT_AMQP.port
    ))
  }

  def getSecurityProvider(secConfig: GlobalSecConfig): JsValue = {
    GestaltProviderBuilder.securityProvider(SecuritySecrets(
      protocol = "http",
      host =   secConfig.hostname,
      port =   secConfig.port,
      key =    secConfig.apiKey,
      secret = secConfig.apiSecret,
      realm = secConfig.realm
    ))
  }

  def getLaserProvider(apiKey: GestaltAPIKey,
                       dbProviderId: UUID, rabbitProviderId: UUID,
                       secProviderId: UUID, caasProviderId: UUID,
                       laserExecutorIds: Seq[UUID], laserEnvId: UUID): JsValue = {
    GestaltProviderBuilder.laserProvider(
      secrets = LaserSecrets(
        dbName = "default-laser-provider",
        monitorExchange = "default-monitor-exchange",
        monitorTopic = "default-monitor-topic",
        responseExchange = "default-response-exchange",
        responseTopic = "default-response-topic",
        listenExchange = "default-listen-exchange",
        listenRoute = "default-listen-route",
        computeUsername = apiKey.apiKey,
        computePassword = apiKey.apiSecret.get,
        computeUrl = s"http://${launcherConfig.vipHostname(META)}:${META.port}",
        laserImage = launcherConfig.dockerImage(LASER),
        laserCpu = LASER.cpu,
        laserMem = LASER.mem,
        laserVHost = launcherConfig.marathon.tld.map("default-laser." + _),
        laserEthernetPort = None,
        executors = Seq.empty,
        globalMinCoolExecutors = Some(launcherConfig.laser.minCoolExecutors),
        globalScaleDownTimeSecs = Some(launcherConfig.laser.scaleDownTimeout)
      ),
      executorIds = laserExecutorIds.map(_.toString),
      laserFQON = s"/root/environments/$laserEnvId/containers",
      securityId = secProviderId.toString,
      computeId = caasProviderId.toString,
      rabbitId = rabbitProviderId.toString,
      dbId = dbProviderId.toString,
      caasType = CaaSTypes.DCOS
    )
  }

  def getKongProvider(dbProviderId: UUID, caasProviderId: UUID): JsValue = {
    GestaltProviderBuilder.kongProvider(
      secrets = KongSecrets(
        image = launcherConfig.dockerImage(KONG),
        dbName = "default-kong-db",
        gatewayVHost = launcherConfig.marathon.tld.map("gtw1." + _),
        serviceVHost = None,
        externalProtocol = Some("https")
      ),
      dbId = dbProviderId.toString,
      computeId = caasProviderId.toString,
      caasType = CaaSTypes.DCOS
    )
  }

  def getPolicyProvider(apiKey: GestaltAPIKey,
                        caasProviderId: UUID,
                        laserProviderId: UUID,
                        rabbitProviderId: UUID): JsValue = {
    GestaltProviderBuilder.policyProvider(
      secrets = PolicySecrets(
        image = launcherConfig.dockerImage(POLICY),
        rabbitExchange = RABBIT_POLICY_EXCHANGE,
        rabbitRoute = RABBIT_POLICY_ROUTE,
        laserUser = apiKey.apiKey,
        laserPassword = apiKey.apiSecret.get
      ),
      computeId = caasProviderId.toString,
      laserId = laserProviderId.toString,
      rabbitId = rabbitProviderId.toString,
      caasType = CaaSTypes.DCOS
    )
  }

  def getGatewayProvider(dbProviderId: UUID, secProviderId: UUID, kongProviderId: UUID, caasProviderId: UUID): JsValue = {
    GestaltProviderBuilder.gatewayProvider(
      secrets = GatewaySecrets(
        image = launcherConfig.dockerImage(API_GATEWAY),
        dbName = "default-gateway-db",
        gatewayVHost = None
      ),
      kongId = kongProviderId.toString,
      dbId = dbProviderId.toString,
      computeId = caasProviderId.toString,
      securityId = secProviderId.toString
    )
  }

  def getExecutorProvider(lr: LaserConfig.LaserRuntime): JsValue = GestaltProviderBuilder.executorPayload(ExecutorSecrets(
    image = lr.image,
    name = lr.name,
    cmd = lr.cmd,
    runtime = lr.runtime,
    metaType = lr.metaType
  ))

}
