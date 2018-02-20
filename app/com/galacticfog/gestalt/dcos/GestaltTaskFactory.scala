package com.galacticfog.gestalt.dcos

import java.util.UUID

import scala.language.implicitConversions
import com.galacticfog.gestalt.dcos.LauncherConfig.{FrameworkService, LaserConfig, ServiceEndpoint, acsServiceAcctFmt}
import com.galacticfog.gestalt.dcos.marathon._
import javax.inject.{Inject, Singleton}

import com.galacticfog.gestalt.cli.GestaltProviderBuilder.CaaSTypes
import com.galacticfog.gestalt.cli._
import com.galacticfog.gestalt.dcos.AppSpec.USER
import com.galacticfog.gestalt.dcos.HealthCheck.{MARATHON_HTTP, MARATHON_TCP}
import com.galacticfog.gestalt.dcos.marathon.MarathonAppPayload.IPPerTaskInfo
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.Logger
import play.api.libs.json._

case class PortSpec(number: Int, name: String, labels: Map[String,String], hostPort: Option[Int] = None)
case class HealthCheck(portIndex: Int, protocol: HealthCheck.HealthCheckProtocol, path: Option[String])
case object HealthCheck {
  sealed trait HealthCheckProtocol
  case object MARATHON_TCP   extends HealthCheckProtocol {override def toString: String = "TCP"}
  case object MESOS_TCP      extends HealthCheckProtocol {override def toString: String = "MESOS_TCP"}

  case object MARATHON_HTTP  extends HealthCheckProtocol {override def toString: String = "HTTP"}
  case object MESOS_HTTP     extends HealthCheckProtocol {override def toString: String = "MESOS_HTTP"}

  case object MARATHON_HTTPS extends HealthCheckProtocol {override def toString: String = "HTTPS"}
  case object MESOS_HTTPS    extends HealthCheckProtocol {override def toString: String = "MESOS_HTTPS"}

  case object COMMAND        extends HealthCheckProtocol {override def toString: String = "COMMAND"}
}

case class AppSpec(name: String,
                   image: String,
                   numInstances: Int = 1,
                   network: AppSpec.Network,
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

case object AppSpec {
  sealed trait Network
  case object HOST   extends Network {override def toString = "HOST"}
  case object BRIDGE extends Network {override def toString = "BRIDGE"}
  case class  USER(network: String) extends Network {override def toString = "USER"}
}

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
    network = launcherConfig.marathon.networkName match {
      case Some("HOST")          => AppSpec.HOST
      case Some("BRIDGE") | None => AppSpec.BRIDGE
      case Some(net)             => AppSpec.USER(net)
    }
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
    Map("HAPROXY_GROUP" -> launcherConfig.marathon.haproxyGroups.getOrElse("external")) ++ vhosts
  }

  def serviceHostname: (ServiceEndpoint) => String = launcherConfig.vipHostname(_)

  def vipDestination(service: ServiceEndpoint) = s"${serviceHostname(service)}:${service.port}"

  def vipLabel: (ServiceEndpoint) => String = launcherConfig.vipLabel(_)

  def vipPort(service: ServiceEndpoint): String = service.port.toString

  def getAppSpec(service: FrameworkService, globalConfig: GlobalConfig): AppSpec = {
    val base = service match {
      case DATA(index) => getData(globalConfig.dbConfig.get, index)
      case ELASTIC     => getElastic
      case RABBIT      => getRabbit
      case SECURITY    => getSecurity(globalConfig.dbConfig.get)
      case META        => getMeta(globalConfig.dbConfig.get, globalConfig.secConfig.get)
      case UI          => getUI
    }
    base.copy(
      env = base.env ++ launcherConfig.extraEnv(service),
      cpus = launcherConfig.resources.cpu.get(service).getOrElse(base.cpus),
      mem  = launcherConfig.resources.mem.get(service).getOrElse(base.mem)
    )
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
    val hostPortMapping = launcherConfig.marathon.networkName match {
      case Some(net) if net != "BRIDGE" => None
      case _ if index == 0 => Some(5432)
      case _ => None
    }
    val proto = DATA(index)
    appSpec(proto).copy(
      volumes = Some(Seq(marathon.Volume(
        containerPath = Some("pgdata"),
        mode = Some("RW"),
        persistent = Some(VolumePersistence(
          size = Some(launcherConfig.database.provisionedSize)
        ))
      ))),
      cpus = launcherConfig.database.provisionedCpu.getOrElse(proto.cpu),
      mem = launcherConfig.database.provisionedMemory.getOrElse(proto.mem),
      residency = Some(Residency(taskLostBehavior = Some(Residency.WAIT_FOREVER))),
      taskKillGracePeriodSeconds = Some(LauncherConfig.DatabaseConfig.DEFAULT_KILL_GRACE_PERIOD),
      env = replEnv ++ Map(
        "POSTGRES_USER" -> dbConfig.username,
        "POSTGRES_PASSWORD" -> dbConfig.password,
        "PGDATA" -> "/mnt/mesos/sandbox/pgdata",
        "PGREPL_ROLE" -> (if (index == 0) "PRIMARY" else "STANDBY"),
        "PGREPL_TOKEN" -> launcherConfig.database.pgreplToken
      ),
      ports = Some(Seq(PortSpec(number = 5432, name = "sql", labels = Map("VIP_0" -> vipLabel(DATA(index))), hostPort = hostPortMapping))),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = launcherConfig(MARATHON_TCP), path = None
      ))
    )
  }

  private[this] def getRabbit: AppSpec = {
    val hostPortMapping = launcherConfig.marathon.networkName match {
      case Some(net) if net != "BRIDGE" => (_: Int) => None
      case _ => Some[Int](_)
    }
    appSpec(RABBIT).copy(
      ports = Some(Seq(
        PortSpec(number = 5672,  name = "service-api", labels = Map("VIP_0" -> vipLabel(RABBIT_AMQP)), hostPort = hostPortMapping(5672)),
        PortSpec(number = 15672, name = "http-api",    labels = Map("VIP_0" -> vipLabel(RABBIT_HTTP)), hostPort = hostPortMapping(15672))
      )),
      healthChecks = Seq(HealthCheck(
        portIndex = 1, protocol = launcherConfig(MARATHON_HTTP), path = Some("/")
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = Some("/"),
        portName = Some("http-api"),
        httpStatusCodesForReady = Some(Seq(200)),
        intervalSeconds = Some(5),
        timeoutSeconds = Some(10)
      ))
    )
  }

  private[this] def getElastic: AppSpec = {
    appSpec(ELASTIC).copy(
      ports = Some(Seq(
        PortSpec(number = 9200, name = "api",     labels = Map("VIP_0" -> vipLabel(ELASTIC_API)), hostPort = None),
        PortSpec(number = 9300, name = "service", labels = Map("VIP_0" -> vipLabel(ELASTIC_SVC)), hostPort = None)
      )),
      healthChecks = Seq.empty,
      readinessCheck = None
    )
  }

  private[this] def getSecurity(dbConfig: GlobalDBConfig): AppSpec = {
    appSpec(SECURITY).copy(
      args = Some(Seq(
        s"-J-Xmx${(SECURITY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m",
        "-Dhttp.port=9455"
      )),
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
        PortSpec(number = 9455, name = "http-api",      labels = Map("VIP_0" -> vipLabel(SECURITY))),
        PortSpec(number = 9455, name = "http-api-dupe", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = launcherConfig(MARATHON_HTTP), path = Some("/health")
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = Some("/init"),
        portName = Some("http-api"),
        httpStatusCodesForReady = Some(Seq(200)),
        intervalSeconds = Some(5),
        timeoutSeconds = Some(10)
      )),
      labels = getVhostLabels(SECURITY)
    )
  }

  private[this] def getMeta(dbConfig: GlobalDBConfig, secConfig: GlobalSecConfig): AppSpec = {
    appSpec(META).copy(
      args = Some(Seq(
        s"-J-Xmx${(META.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m",
        "-Dhttp.port=14374"
      )),
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
        PortSpec(number = 14374, name = "http-api", labels = Map("VIP_0" -> vipLabel(META))),
        PortSpec(number = 14374, name = "http-api-dupe", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(portIndex = 0, protocol = launcherConfig(MARATHON_HTTP), path = Some("/health"))),
      readinessCheck = Some(MarathonReadinessCheck(
        path = Some("/health"),
        portName = Some("http-api"),
        httpStatusCodesForReady = Some(Seq(200,401,403)),
        intervalSeconds = Some(5),
        timeoutSeconds = Some(10)
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
        protocol = launcherConfig(MARATHON_HTTP),
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = Some("/#/login"),
        portName = Some("http"),
        intervalSeconds = Some(5)
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
    val ipPerTask = app.network match {
      case AppSpec.HOST | AppSpec.BRIDGE => None
      case USER(net)                     => Some(
        IPPerTaskInfo(
          networkName = Some(net)
        )
      )
    }
    val isPortMapped = app.network != AppSpec.HOST
    MarathonAppPayload(
      id = Some("/" + launcherConfig.marathon.appGroup + "/" + app.name),
      args = app.args,
      env = Some(JsObject(app.env.mapValues(JsString(_)))),
      instances = Some(app.numInstances),
      cpus = Some(app.cpus),
      cmd = app.cmd,
      mem = Some(app.mem),
      disk = Some(0),
      residency = app.residency,
      container = Some(MarathonContainerInfo(
        `type` = Some(MarathonContainerInfo.Types.DOCKER),
        volumes = app.volumes,
        docker = Some(MarathonDockerContainer(
          image = Some(app.image),
          network = Some(app.network.toString),
          privileged = Some(false),
          forcePullImage = Some(true),
          portMappings = if (isPortMapped) app.ports.map {_.map(
            p => DockerPortMapping(containerPort = Some(p.number), name = Some(p.name), protocol = Some("tcp"), labels = Some(p.labels), hostPort = p.hostPort)
          ) } else None
        ))
      )),
      labels = Some(app.labels),
      healthChecks = Some(app.healthChecks.map( hc => MarathonHealthCheck(
        path = hc.path,
        protocol = Some(hc.protocol.toString),
        portIndex = Some(hc.portIndex),
        port = None,
        gracePeriodSeconds = Some(300),
        intervalSeconds = Some(30),
        timeoutSeconds = Some(15),
        maxConsecutiveFailures = Some(4)
      ))),
      readinessCheck = app.readinessCheck,
      requirePorts = Some(true),
      portDefinitions = if (!isPortMapped) app.ports.map {_.map(
        p => PortDefinition(port = Some(p.number), protocol = Some("tcp"), name = Some(p.name), labels = Some(p.labels))
      )} else Some(Seq.empty),
      taskKillGracePeriodSeconds = app.taskKillGracePeriodSeconds,
      ipAddress = ipPerTask
    )
  }

  def getCaasProvider(): JsValue = {
    GestaltProviderBuilder.caasProvider(CaaSSecrets(
      url = Some(launcherConfig.marathon.baseUrl),
      username = Some("unused"),
      password = Some("unused"),
      auth = launcherConfig.dcosAuth.map(
        auth => Json.obj(
          "scheme" -> "acs",
          "service_account_id" -> auth.uid,
          "private_key" -> auth.private_key,
          "dcos_base_url" -> auth.login_endpoint.stripSuffix("/acs/api/v1/auth/login")
        )
      ),
      acceptAnyCert = launcherConfig.acceptAnyCertificate,
      kubeconfig = None,
      appGroupPrefix = Some(launcherConfig.marathon.appGroup),
      marathonFrameworkName = Some(launcherConfig.marathon.frameworkName),
      dcosClusterName = Some(launcherConfig.marathon.clusterName),
      networks = launcherConfig.marathon.networkList,
      loadBalancerGroups = launcherConfig.marathon.haproxyGroups.map(_.split(",")),
      dcosSecretSupport = launcherConfig.dcos.secretSupport,
      dcosSecretUrl = launcherConfig.dcos.secretUrl,
      dcosSecretStore = launcherConfig.dcos.secretStore
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

  def getLogProvider(caasProviderId: UUID, esConfig: GlobalElasticConfig): Option[JsValue] = {
    if (launcherConfig.logging.provisionProvider) Some(GestaltProviderBuilder.loggingProvider(
      secrets = LoggingSecrets(
        esConfig = LoggingSecrets.ESConfig(
          esClusterName = esConfig.clusterName,
          esComputeType = "dcos",
          esServiceHost = esConfig.hostname,
          esServicePort = esConfig.portSvc,
          esColdDays = 14,
          esHotDays = 7,
          esSnapshotRepo = "s3_repository"
        ),
        dcosConfig = Some(LoggingSecrets.DCOSConfig(
          dcosSvcAccountCreds = launcherConfig.dcosAuth.map(c => Json.toJson(c).toString),
          dcosAuth = None,
          dcosHost = "leader.mesos",
          dcosProtocol = "http",
          dcosPort = 80
        )),
        serviceConfig = ServiceConfig(
          image = launcherConfig.dockerImage(LOG),
          network = launcherConfig.marathon.networkName,
          healthCheckProtocol = Some(if (launcherConfig.marathon.mesosHealthChecks) "MESOS_HTTP" else "HTTP"),
          vhost = launcherConfig.marathon.tld.map("default-logging." + _),
          vhostProto = launcherConfig.marathon.marathonLbProto,
          cpus = launcherConfig.resources.cpu.get(LOG) orElse Some(LOG.cpu),
          memory = launcherConfig.resources.mem.get(LOG) orElse Some(LOG.mem)
        )
      ),
      computeId = caasProviderId.toString,
      caasType = CaaSTypes.DCOS,
      providerName = Some("default-logging"),
      extraEnv = launcherConfig.extraEnv(LOG)
    )) else None
  }

  def getLaserProvider(apiKey: GestaltAPIKey,
                       dbProviderId: UUID, rabbitProviderId: UUID,
                       secProviderId: UUID, caasProviderId: UUID,
                       laserExecutorIds: Seq[UUID], laserEnvId: UUID,
                       esConfig: Option[GlobalElasticConfig] = None ): JsValue = {
    val esc = esConfig.filter(_ => launcherConfig.logging.configureLaser)
    GestaltProviderBuilder.laserProvider(
      secrets = LaserSecrets(
        serviceConfig = LaserSecrets.ServiceConfig(
          dbName = "default-laser-provider",
          laserImage = launcherConfig.dockerImage(LASER),
          laserCpu = launcherConfig.resources.cpu.get(LASER) getOrElse LASER.cpu,
          laserMem = launcherConfig.resources.mem.get(LASER) getOrElse LASER.mem,
          laserVHost = launcherConfig.marathon.tld.map("default-laser." + _),
          laserEthernetPort = None,
          serviceHostOverride = launcherConfig.laser.serviceHostOverride,
          servicePortOverride = launcherConfig.laser.servicePortOverride
        ),
        caasConfig = LaserSecrets.CaaSConfig(
          computeUrl = s"http://${launcherConfig.vipHostname(META)}:${META.port}",
          computeUsername = apiKey.apiKey,
          computePassword = apiKey.apiSecret.get,
          network = launcherConfig.marathon.networkName,
          healthCheckProtocol = Some(if (launcherConfig.marathon.mesosHealthChecks) "MESOS_HTTP" else "HTTP")
        ),
        queueConfig = LaserSecrets.QueueConfig(
          monitorExchange = "default-monitor-exchange",
          monitorTopic = "default-monitor-topic",
          responseExchange = "default-response-exchange",
          responseTopic = "default-response-topic",
          listenExchange = "default-listen-exchange",
          listenRoute = "default-listen-route"
        ),
        schedulerConfig = LaserSecrets.SchedulerConfig(
          globalMinCoolExecutors = None,
          globalScaleDownTimeSecs = launcherConfig.laser.scaleDownTimeout,
          laserAdvertiseHostname = None,
          laserMaxCoolConnectionTime = Some(launcherConfig.laser.maxCoolConnectionTime),
          laserExecutorHeartbeatTimeout = Some(launcherConfig.laser.executorHeartbeatTimeout),
          laserExecutorHeartbeatPeriod = Some(launcherConfig.laser.executorHeartbeatPeriod),
          laserExecutorPort = if (launcherConfig.marathon.networkName.isDefined) Some(60500) else None,
          esHost = esc.map(_.hostname),
          esPort = esc.map(_.portApi),
          esProtocol = esc.map(_.protocol)
        ),
        executors = Seq.empty
      ),
      executorIds = laserExecutorIds.map(_.toString),
      laserFQON = s"/root/environments/$laserEnvId/containers",
      securityId = secProviderId.toString,
      computeId = caasProviderId.toString,
      rabbitId = rabbitProviderId.toString,
      dbId = dbProviderId.toString,
      caasType = CaaSTypes.DCOS,
      providerName = Some("laser"),
      extraEnv = Map(
        "DEFAULT_EXECUTOR_MEM" -> launcherConfig.laser.defaultExecutorMem.toString,
        "DEFAULT_EXECUTOR_CPU" -> launcherConfig.laser.defaultExecutorCpu.toString
      ) ++ launcherConfig.extraEnv(LASER)
    )
  }

  def getKongProvider(dbProviderId: UUID, caasProviderId: UUID): JsValue = {
    GestaltProviderBuilder.kongProvider(
      secrets = KongSecrets(
        kongConfig = KongSecrets.KongConfig(
          dbName = "default-kong-db",
          gatewayVHost = launcherConfig.marathon.tld.map("gtw1." + _),
          serviceVHost = None,
          externalProtocol = Some("https"),
          servicePort = None
        ),
        serviceConfig = ServiceConfig(
          image = launcherConfig.dockerImage(KONG),
          network = launcherConfig.marathon.networkName,
          healthCheckProtocol = Some(if (launcherConfig.marathon.mesosHealthChecks) "MESOS_HTTP" else "HTTP"),
          cpus = launcherConfig.resources.cpu.get(KONG) orElse Some(KONG.cpu),
          memory = launcherConfig.resources.mem.get(KONG) orElse Some(KONG.mem)
        )
      ),
      dbId = dbProviderId.toString,
      computeId = caasProviderId.toString,
      caasType = CaaSTypes.DCOS,
      providerName = Some("kong"),
      extraEnv = launcherConfig.extraEnv(KONG)
    )
  }

  def getPolicyProvider(apiKey: GestaltAPIKey,
                        caasProviderId: UUID,
                        laserProviderId: UUID,
                        rabbitProviderId: UUID): JsValue = {
    GestaltProviderBuilder.policyProvider(
      secrets = PolicySecrets(
        rabbitConfig = PolicySecrets.RabbitConfig(
          rabbitExchange = RABBIT_POLICY_EXCHANGE,
          rabbitRoute = RABBIT_POLICY_ROUTE
        ),
        laserConfig = PolicySecrets.LaserConfig(
          laserUser = apiKey.apiKey,
          laserPassword = apiKey.apiSecret.get
        ),
        serviceConfig = ServiceConfig(
          image = launcherConfig.dockerImage(POLICY),
          network = launcherConfig.marathon.networkName,
          healthCheckProtocol = Some(if (launcherConfig.marathon.mesosHealthChecks) "MESOS_HTTP" else "HTTP"),
          cpus = launcherConfig.resources.cpu.get(POLICY) orElse Some(POLICY.cpu),
          memory = launcherConfig.resources.mem.get(POLICY) orElse Some(POLICY.mem)
        )
      ),
      computeId = caasProviderId.toString,
      laserId = laserProviderId.toString,
      rabbitId = rabbitProviderId.toString,
      caasType = CaaSTypes.DCOS,
      providerName = Some("policy"),
      extraEnv = launcherConfig.extraEnv(POLICY)
    )
  }

  def getGatewayProvider(dbProviderId: UUID, secProviderId: UUID, kongProviderId: UUID, caasProviderId: UUID): JsValue = {
    GestaltProviderBuilder.gatewayProvider(
      secrets = GatewaySecrets(
        gwmConfig = GatewaySecrets.GatewayConfig(
          dbName = "default-gateway-db",
          gatewayVHost = None
        ),
        serviceConfig = ServiceConfig(
          image = launcherConfig.dockerImage(API_GATEWAY),
          network = launcherConfig.marathon.networkName,
          healthCheckProtocol = Some(if (launcherConfig.marathon.mesosHealthChecks) "MESOS_HTTP" else "HTTP"),
          cpus = launcherConfig.resources.cpu.get(API_GATEWAY) orElse Some(API_GATEWAY.cpu),
          memory = launcherConfig.resources.mem.get(API_GATEWAY) orElse Some(API_GATEWAY.mem)
        )
      ),
      kongId = kongProviderId.toString,
      dbId = dbProviderId.toString,
      computeId = caasProviderId.toString,
      securityId = secProviderId.toString,
      providerName = Some("gwm"),
      extraEnv = launcherConfig.extraEnv(API_GATEWAY)
    )
  }

  def getExecutorProvider(lr: LaserConfig.LaserRuntime): JsValue = GestaltProviderBuilder.executorPayload(ExecutorSecrets(
    image = lr.image,
    name = lr.name,
    cmd = lr.cmd,
    runtime = lr.runtime,
    metaType = lr.metaType,
    extraEnv = lr.laserExecutor.map(launcherConfig.extraEnv).getOrElse(Map.empty)
  ))

}
