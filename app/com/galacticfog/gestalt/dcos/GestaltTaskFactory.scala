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
import play.api.libs.json.{JsObject, JsValue, Json}

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

case class GlobalConfig( dbConfig: Option[GlobalDBConfig],
                         secConfig: Option[GlobalSecConfig] ) {
  def withDb(dbConfig: GlobalDBConfig): GlobalConfig = this.copy(
    dbConfig = Some(dbConfig)
  )

  def withSec(secConfig: GlobalSecConfig): GlobalConfig = this.copy(
    secConfig = Some(secConfig)
  )
}

object GlobalConfig {
  def empty: GlobalConfig = GlobalConfig(None,None)
  def apply(): GlobalConfig = GlobalConfig.empty
}

case class GlobalDBConfig( hostname: String,
                           port: Int,
                           username: String,
                           password: String,
                           prefix: String )

case class GlobalSecConfig( hostname: String,
                            port: Int,
                            apiKey: String,
                            apiSecret: String,
                            realm: String )

@Singleton
class GestaltTaskFactory @Inject() ( launcherConfig: LauncherConfig ) {

  val RABBIT_EXCHANGE = "policy-exchange"

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
        "RABBIT_EXCHANGE"  -> RABBIT_EXCHANGE,
        "RABBIT_ROUTE"     -> "policy"
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

//  private[this] def getKong(globals: JsValue): AppSpec = {
//    val dbConfig = GlobalDBConfig(globals)
//    appSpec(KONG).copy(
//      env = Map(
//        "POSTGRES_HOST"     -> s"${dbConfig.hostname}",
//        "POSTGRES_PORT"     -> s"${dbConfig.port}",
//        "POSTGRES_DATABASE" -> s"${dbConfig.prefix}kong",
//        "POSTGRES_USER"     -> s"${dbConfig.username}",
//        "POSTGRES_PASSWORD" -> s"${dbConfig.password}"
//      ),
//      ports = Some(Seq(
//        PortSpec(number = 8000, name = "gateway-api", labels = Map("VIP_0" -> vipLabel(KONG_GATEWAY))),
//        PortSpec(number = 8001, name = "service-api", labels = Map("VIP_0" -> vipLabel(KONG_SERVICE)))
//      )),
//      healthChecks = Seq(HealthCheck(portIndex = 1, protocol = MARATHON_HTTP, path = Some("/cluster"))),
//      readinessCheck = None,
//      labels = getVhostLabels(KONG) ++ Map(
//        "HAPROXY_0_BACKEND_HEAD" -> "backend {backend}\n  balance {balance}\n  mode {mode}\n  timeout server 30m\n  timeout client 30m\n",
//        "HAPROXY_1_ENABLED" -> "false"
//      )
//    )
//  }

//  private[this] def getPolicy(globals: JsValue): AppSpec = {
//    val secConfig = (globals \ "security")
//    appSpec(POLICY).copy(
//      args = Some(Seq(s"-J-Xmx${(POLICY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
//      env = Map(
//        "LAMBDA_HOST"     -> serviceHostname(LASER),
//        "LAMBDA_PORT"     -> vipPort(LASER),
//        "LAMBDA_USER"     -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
//        "LAMBDA_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
//        //
//        "META_PROTOCOL" -> "http",
//        "META_HOST" -> serviceHostname(META),
//        "META_PORT" -> vipPort(META),
//        "META_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
//        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
//        //
//        "BINDING_UPDATE_SECONDS" -> "20",
//        "CONNECTION_CHECK_TIME_SECONDS" -> "60",
//        "POLICY_MAX_WORKERS" -> "20",
//        "POLICY_MIN_WORKERS" -> "5",
//        //
//        "RABBIT_HOST" -> serviceHostname(RABBIT_AMQP),
//        "RABBIT_PORT" -> vipPort(RABBIT_AMQP),
//        "RABBIT_EXCHANGE" -> RABBIT_EXCHANGE,
//        "RABBIT_ROUTE" -> "policy"
//      ),
//      ports = Some(Seq(
//        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(POLICY)))
//      )),
//      healthChecks = Seq(HealthCheck(
//        path = Some("/health"),
//        protocol = MARATHON_HTTP,
//        portIndex = 0
//      )),
//      readinessCheck = Some(MarathonReadinessCheck(
//        path = "/health",
//        portName = "http-api",
//        intervalSeconds = 5
//      ))
//    )
//  }

//  private[this] def getLaser(globals: JsValue): AppSpec = {
//    val laserSchedulerFrameworkName = {
//      launcherConfig.marathon.appGroup.replaceAll("[/]","-") + "-" + "laser"
//    }
//    val dbConfig = GlobalDBConfig(globals)
//    val secConfig = (globals \ "security")
//
//    val enabledRuntimes = launcherConfig.laser.enabledRuntimes.zipWithIndex.foldLeft(Map.empty[String,String]){
//      case (vars, (runtime,i)) => vars ++ Map(
//        s"EXECUTOR_${i}_NAME"    -> runtime.name,
//        s"EXECUTOR_${i}_RUNTIME" -> runtime.runtime,
//        s"EXECUTOR_${i}_IMAGE"   -> runtime.image,
//        s"EXECUTOR_${i}_CMD"     -> runtime.cmd
//      )
//    }
//
//    appSpec(LASER).copy(
//      args = None,
//      cmd = Some("LIBPROCESS_PORT=$PORT1 ./bin/gestalt-laser -Dhttp.port=$PORT0 -J-Xmx" + (LASER.mem / launcherConfig.marathon.jvmOverheadFactor).toInt + "m"),
//      env = gestaltSecurityEnvVars(globals) ++ enabledRuntimes ++ Map(
//        "LAMBDA_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
//        "LAMBDA_DATABASE_PORT"     -> s"${dbConfig.port}",
//        "LAMBDA_DATABASE_NAME"     -> s"${dbConfig.prefix}laser",
//        "LAMBDA_DATABASE_USER"     -> s"${dbConfig.username}",
//        "LAMBDA_DATABASE_PASSWORD" -> s"${dbConfig.password}",
//        "LAMBDA_FLYWAY_MIGRATE"    -> "true",
//        //
//        "META_PROTOCOL" -> "http",
//        "META_HOSTNAME" -> serviceHostname(META),
//        "META_PORT"     -> vipPort(META),
//        "META_USER"     -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
//        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
//        //
//        "RABBIT_HOST" -> serviceHostname(RABBIT_AMQP),
//        "RABBIT_PORT" -> vipPort(RABBIT_AMQP),
//        "RABBIT_MONITOR_EXCHANGE" -> "lambda-monitor-exchange",
//        "RABBIT_MONITOR_TOPIC" -> "monitor",
//        "RABBIT_RESPONSE_EXCHANGE" -> "response",
//        "RABBIT_RESPONSE_TOPIC" -> "response",
//        "RABBIT_LISTEN_ROUTE" -> "lambda-input",
//        "RABBIT_EXCHANGE" -> "lambda-executor-exchange",
//        //
//        "MANAGEMENT_PROTOCOL" -> "ws",
//        "MIN_COOL_EXECUTORS" -> launcherConfig.laser.minCoolExecutors.toString,
//        "SCALE_DOWN_TIME_SECONDS" -> launcherConfig.laser.scaleDownTimeout.toString,
//        "MIN_PORT_RANGE" -> launcherConfig.laser.minPortRange.toString,
//        "MAX_PORT_RANGE" -> launcherConfig.laser.maxPortRange.toString,
//        //
//        "MAX_LAMBDAS_PER_OFFER" -> "6",
//        "MESOS_MASTER_CONNECTION" -> "zk://master.mesos:2181/mesos",
//        "MESOS_NATIVE_JAVA_LIBRARY" -> "/usr/lib/libmesos.so",
//        "MESOS_NATIVE_LIBRARY" -> "/usr/lib/libmesos.so",
//        "MESOS_ROLE" -> "*",
//        "SCHEDULER_NAME" -> laserSchedulerFrameworkName,
//        "OFFER_TTL" -> "5",
//        //
//        "EXECUTOR_HEARTBEAT_MILLIS" -> "1000",
//        "EXECUTOR_HEARTBEAT_TIMEOUT" -> "5000",
//        "CACHE_CHECK_SECONDS" -> "30",
//        "CACHE_EXPIRE_SECONDS" -> "900"
//      ),
//      network = ContainerInfo.DockerInfo.Network.HOST,
//      ports = Some(Seq(
//        PortSpec(number = 0, name = "http-api", labels = Map("VIP_0" -> vipLabel(LASER))),
//        PortSpec(number = 0, name = "libprocess", labels = Map())
//      )),
//      healthChecks = Seq(HealthCheck(
//        path = Some("/health"),
//        protocol = MARATHON_HTTP,
//        portIndex = 0
//      )),
//      readinessCheck = Some(MarathonReadinessCheck(
//        path = "/health",
//        portName = "http-api",
//        intervalSeconds = 5
//      )),
//      labels = getVhostLabels(LASER) ++ Map()
//    )
//  }

//  private[this] def getApiGateway(globals: JsValue): AppSpec = {
//    val dbConfig = GlobalDBConfig(globals)
//    appSpec(API_GATEWAY).copy(
//      args = Some(Seq(s"-J-Xmx${(API_GATEWAY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
//      env = gestaltSecurityEnvVars(globals) ++ Map(
//        "GATEWAY_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
//        "GATEWAY_DATABASE_PORT"     -> s"${dbConfig.port}",
//        "GATEWAY_DATABASE_NAME"     -> s"${dbConfig.prefix}apigateway",
//        "GATEWAY_DATABASE_PASSWORD" -> s"${dbConfig.password}",
//        "GATEWAY_DATABASE_USER"     -> s"${dbConfig.username}",
//        "GATEWAY_DATABASE_MIGRATE"  -> "true"
//      ),
//      ports = Some(Seq(
//        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(API_GATEWAY)))
//      )),
//      healthChecks = Seq(HealthCheck(
//        path = Some("/health"),
//        protocol = MARATHON_HTTP,
//        portIndex = 0
//      )),
//      readinessCheck = Some(MarathonReadinessCheck(
//        path = "/health",
//        portName = "http-api",
//        intervalSeconds = 5
//      ))
//    )
//  }

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
