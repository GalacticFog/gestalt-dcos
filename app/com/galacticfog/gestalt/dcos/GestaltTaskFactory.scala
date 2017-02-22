package com.galacticfog.gestalt.dcos

import scala.language.implicitConversions
import java.util.UUID

import com.galacticfog.gestalt.dcos.LauncherConfig.{FrameworkService, ServiceEndpoint}
import com.galacticfog.gestalt.dcos.marathon._
import javax.inject.{Inject, Singleton}
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.Logger
import play.api.libs.json.JsValue

case class PortSpec(number: Int, name: String, labels: Map[String,String])
case class HealthCheck(portIndex: Int, protocol: String, path: String)

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

case class GlobalDBConfig(hostname: String,
                          port: Int,
                          username: String,
                          password: String,
                          prefix: String)

case object GlobalDBConfig {
  def apply(global: JsValue): GlobalDBConfig = GlobalDBConfig(
    hostname = (global \ "database" \ "hostname").asOpt[String].getOrElse("data.gestalt.marathon.mesos"),
    port = (global \ "database" \ "port").asOpt[Int].getOrElse(5432),
    username = (global \ "database" \ "username").asOpt[String].getOrElse("gestaltdev"),
    password = (global \ "database" \ "password").asOpt[String].getOrElse("letmein"),
    prefix = (global \ "database" \ "prefix").asOpt[String].getOrElse("gestalt-")
  )
}

@Singleton
class GestaltTaskFactory @Inject() ( launcherConfig: LauncherConfig ) {

  val RABBIT_EXCHANGE = "policy-exchange"

  val TLD = launcherConfig.marathon.tld

  val appGroup = launcherConfig.marathon.appGroup

  val gestaltFrameworkEnsembleVersion = launcherConfig.gestaltFrameworkVersion

  Logger.info("gestalt-framework-version: " + gestaltFrameworkEnsembleVersion)

  import launcherConfig.dockerImage
  import LauncherConfig.Services._
  import LauncherConfig.Executors._

  def appSpec(service: FrameworkService) = AppSpec(
    name = service.name,
    args = Some(Seq()),
    image = launcherConfig.dockerImage(service),
    cpus = service.cpu,
    mem = service.mem,
    network = ContainerInfo.DockerInfo.Network.BRIDGE
  )

  def getVhostLabels(service: FrameworkService): Map[String,String] = {
     TLD match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"${service.name}.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map(
        "HAPROXY_GROUP" -> "external"
      )
    }
  }

  def serviceHostname = launcherConfig.vipHostname(_)

  def vipDestination(service: ServiceEndpoint) = s"${serviceHostname(service)}:${service.port}"

  def vipLabel = launcherConfig.vipLabel(_)

  def vipPort(service: ServiceEndpoint) = service.port.toString

  def getAppSpec(service: FrameworkService, globals: JsValue): AppSpec = {
    service match {
      case DATA(index) => getData(globals, index)
      case RABBIT      => getRabbit(globals)
      case SECURITY    => getSecurity(globals)
      case META        => getMeta(globals)
      case API_PROXY   => getApiProxy(globals)
      case UI          => getUI(globals)
      case KONG        => getKong(globals)
      case API_GATEWAY => getApiGateway(globals)
      case LASER       => getLaser(globals)
      case POLICY      => getPolicy(globals)
    }
  }

  private[this] def gestaltSecurityEnvVars(globals: JsValue): Map[String, String] = {
    val secConfig = (globals \ "security")
    Map(
      "GESTALT_SECURITY_PROTOCOL" -> "http",
      "GESTALT_SECURITY_HOSTNAME" -> serviceHostname(SECURITY),
      "GESTALT_SECURITY_PORT" -> vipPort(SECURITY),
      "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
      "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
      "GESTALT_SECURITY_REALM" ->
        TLD.map("https://security." + _)
          .orElse((secConfig \ "realm").asOpt[String])
          .getOrElse(s"http://${vipDestination(SECURITY)}")
    )
  }

  private[this] def getData(globals: JsValue, index: Int): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
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
      ports = Some(Seq(PortSpec(number = 5432, name = "sql", labels = Map("VIP_0" -> vipLabel(DATA(index)))))),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = "TCP", path = ""
      ))
    )
  }

  private[this] def getSecurity(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    appSpec(SECURITY).copy(
      args = Some(Seq(s"-J-Xmx${(SECURITY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = Map(
        "DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "DATABASE_PORT"     -> s"${dbConfig.port}",
        "DATABASE_NAME"     -> s"${dbConfig.prefix}security",
        "DATABASE_USERNAME" -> s"${dbConfig.username}",
        "DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "OAUTH_RATE_LIMITING_AMOUNT" -> (secConfig \ "oauth" \ "rateLimitingAmount").asOpt[Int].map(_.toString).getOrElse("100"),
        "OAUTH_RATE_LIMITING_PERIOD" -> (secConfig \ "oauth" \ "rateLimitingPeriod").asOpt[Int].map(_.toString).getOrElse("1")
      ),
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(SECURITY))))),
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = "HTTP", path = "/health"
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

  private[this] def getMeta(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    appSpec(META).copy(
      args = Some(Seq(s"-J-Xmx${(META.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = gestaltSecurityEnvVars(globals) ++ Map(
        "DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "DATABASE_PORT"     -> s"${dbConfig.port}",
        "DATABASE_NAME"     -> s"${dbConfig.prefix}meta",
        "DATABASE_USERNAME" -> s"${dbConfig.username}",
        "DATABASE_PASSWORD" -> s"${dbConfig.password}",
        //
        "GESTALT_APIGATEWAY" -> s"http://${vipDestination(API_GATEWAY)}",
        "GESTALT_LAMBDA"     -> s"http://${vipDestination(LASER)}",
        //
        "RABBIT_HOST"      -> serviceHostname(RABBIT_AMQP),
        "RABBIT_PORT"      -> vipPort(RABBIT_AMQP),
        "RABBIT_HTTP_PORT" -> vipPort(RABBIT_HTTP),
        "RABBIT_EXCHANGE"  -> RABBIT_EXCHANGE,
        "RABBIT_ROUTE"     -> "policy"
      ),
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(META))))),
      healthChecks = Seq(HealthCheck(portIndex = 0, protocol = "HTTP", path = "/health")),
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

  private[this] def getKong(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    appSpec(KONG).copy(
      env = Map(
        "POSTGRES_HOST"     -> s"${dbConfig.hostname}",
        "POSTGRES_PORT"     -> s"${dbConfig.port}",
        "POSTGRES_DATABASE" -> s"${dbConfig.prefix}kong",
        "POSTGRES_USER"     -> s"${dbConfig.username}",
        "POSTGRES_PASSWORD" -> s"${dbConfig.password}"
      ),
      ports = Some(Seq(
        PortSpec(number = 8000, name = "gateway-api", labels = Map("VIP_0" -> vipLabel(KONG_GATEWAY))),
        PortSpec(number = 8001, name = "service-api", labels = Map("VIP_0" -> vipLabel(KONG_SERVICE)))
      )),
      healthChecks = Seq(HealthCheck(portIndex = 1, protocol = "HTTP", path = "/cluster")),
      readinessCheck = None,
      labels = getVhostLabels(KONG) ++ Map(
        "HAPROXY_0_BACKEND_HEAD" -> "backend {backend}\n  balance {balance}\n  mode {mode}\n  timeout server 30m\n  timeout client 30m\n"
      )
    )
  }

  private[this] def getPolicy(globals: JsValue): AppSpec = {
    val secConfig = (globals \ "security")
    appSpec(POLICY).copy(
      args = Some(Seq(s"-J-Xmx${(POLICY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = Map(
        "LAMBDA_HOST"     -> serviceHostname(LASER),
        "LAMBDA_PORT"     -> vipPort(LASER),
        "LAMBDA_USER"     -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "LAMBDA_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "META_PROTOCOL" -> "http",
        "META_HOST" -> serviceHostname(META),
        "META_PORT" -> vipPort(META),
        "META_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "BINDING_UPDATE_SECONDS" -> "20",
        "CONNECTION_CHECK_TIME_SECONDS" -> "60",
        "POLICY_MAX_WORKERS" -> "20",
        "POLICY_MIN_WORKERS" -> "5",
        //
        "RABBIT_HOST" -> serviceHostname(RABBIT_AMQP),
        "RABBIT_PORT" -> vipPort(RABBIT_AMQP),
        "RABBIT_EXCHANGE" -> RABBIT_EXCHANGE,
        "RABBIT_ROUTE" -> "policy"
      ),
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(POLICY)))
      )),
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      ))
    )
  }

  private[this] def getLaser(globals: JsValue): AppSpec = {
    val laserSchedulerFrameworkName = {
      launcherConfig.marathon.appGroup.replaceAll("[/]","-") + "-" + "laser"
    }
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    appSpec(LASER).copy(
      args = None,
      cmd = Some("LIBPROCESS_PORT=$PORT1 ./bin/gestalt-laser -Dhttp.port=$PORT0 -J-Xmx" + (LASER.mem / launcherConfig.marathon.jvmOverheadFactor).toInt + "m"),
      env = gestaltSecurityEnvVars(globals) ++ Map(
        "LAMBDA_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "LAMBDA_DATABASE_PORT"     -> s"${dbConfig.port}",
        "LAMBDA_DATABASE_NAME"     -> s"${dbConfig.prefix}laser",
        "LAMBDA_DATABASE_USER"     -> s"${dbConfig.username}",
        "LAMBDA_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "LAMBDA_FLYWAY_MIGRATE"    -> "true",
        //
        "META_PROTOCOL" -> "http",
        "META_HOSTNAME" -> serviceHostname(META),
        "META_PORT"     -> vipPort(META),
        "META_USER"     -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "RABBIT_HOST" -> serviceHostname(RABBIT_AMQP),
        "RABBIT_PORT" -> vipPort(RABBIT_AMQP),
        "RABBIT_MONITOR_EXCHANGE" -> "lambda-monitor-exchange",
        "RABBIT_MONITOR_TOPIC" -> "monitor",
        "RABBIT_RESPONSE_EXCHANGE" -> "response",
        "RABBIT_RESPONSE_TOPIC" -> "response",
        "RABBIT_LISTEN_ROUTE" -> "lambda-input",
        "RABBIT_EXCHANGE" -> "lambda-executor-exchange",
        //
        "MANAGEMENT_PROTOCOL" -> "ws",
        "MIN_COOL_EXECUTORS" -> launcherConfig.laser.minCoolExecutors.toString,
        "SCALE_DOWN_TIME_SECONDS" -> launcherConfig.laser.scaleDownTimeout.toString,
        "MIN_PORT_RANGE" -> launcherConfig.laser.minPortRange.toString,
        "MAX_PORT_RANGE" -> launcherConfig.laser.maxPortRange.toString,
        //
        "MAX_LAMBDAS_PER_OFFER" -> "6",
        "MESOS_MASTER_CONNECTION" -> "zk://master.mesos:2181/mesos",
        "MESOS_NATIVE_JAVA_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_NATIVE_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_ROLE" -> "*",
        "SCHEDULER_NAME" -> laserSchedulerFrameworkName,
        "OFFER_TTL" -> "5",
        //
        "EXECUTOR_HEARTBEAT_MILLIS" -> "1000",
        "EXECUTOR_HEARTBEAT_TIMEOUT" -> "5000",
        "CACHE_CHECK_SECONDS" -> "30",
        "CACHE_EXPIRE_SECONDS" -> "900",
        //
        "EXECUTOR_0_CMD"     -> "bin/gestalt-laser-executor-js",
        "EXECUTOR_0_IMAGE"   -> dockerImage(EXECUTOR_JS),
        "EXECUTOR_0_NAME"    -> "js-executor",
        "EXECUTOR_0_RUNTIME" -> "nodejs",
        "EXECUTOR_1_CMD"     -> "bin/gestalt-laser-executor-jvm",
        "EXECUTOR_1_IMAGE"   -> dockerImage(EXECUTOR_JVM),
        "EXECUTOR_1_NAME"    -> "jvm-executor",
        "EXECUTOR_1_RUNTIME" -> "java;scala",
        "EXECUTOR_2_CMD"     -> "bin/gestalt-laser-executor-dotnet",
        "EXECUTOR_2_IMAGE"   -> dockerImage(EXECUTOR_DOTNET),
        "EXECUTOR_2_NAME"    -> "dotnet-executor",
        "EXECUTOR_2_RUNTIME" -> "csharp;dotnet",
        "EXECUTOR_3_CMD"     -> "bin/gestalt-laser-executor-python",
        "EXECUTOR_3_IMAGE"   -> dockerImage(EXECUTOR_PYTHON),
        "EXECUTOR_3_NAME"    -> "python-executor",
        "EXECUTOR_3_RUNTIME" -> "python",
        "EXECUTOR_4_CMD"     -> "bin/gestalt-laser-executor-ruby",
        "EXECUTOR_4_IMAGE"   -> dockerImage(EXECUTOR_RUBY),
        "EXECUTOR_4_NAME"    -> "ruby-executor",
        "EXECUTOR_4_RUNTIME" -> "ruby",
        "EXECUTOR_5_CMD"     -> "bin/gestalt-laser-executor-golang",
        "EXECUTOR_5_IMAGE"   -> dockerImage(EXECUTOR_GOLANG),
        "EXECUTOR_5_NAME"    -> "golang-executor",
        "EXECUTOR_5_RUNTIME" -> "golang"
      ),
      network = ContainerInfo.DockerInfo.Network.HOST,
      ports = Some(Seq(
        PortSpec(number = 0, name = "http-api", labels = Map("VIP_0" -> vipLabel(LASER))),
        PortSpec(number = 0, name = "libprocess", labels = Map())
      )),
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      )),
      labels = getVhostLabels(LASER) ++ Map(
        "DCOS_PACKAGE_FRAMEWORK_NAME" -> laserSchedulerFrameworkName,
        "DCOS_PACKAGE_IS_FRAMEWORK" -> "true"
      )
    )
  }

  private[this] def getApiGateway(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    appSpec(API_GATEWAY).copy(
      args = Some(Seq(s"-J-Xmx${(API_GATEWAY.mem / launcherConfig.marathon.jvmOverheadFactor).toInt}m")),
      env = gestaltSecurityEnvVars(globals) ++ Map(
        "GATEWAY_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "GATEWAY_DATABASE_PORT"     -> s"${dbConfig.port}",
        "GATEWAY_DATABASE_NAME"     -> s"${dbConfig.prefix}apigateway",
        "GATEWAY_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "GATEWAY_DATABASE_USER"     -> s"${dbConfig.username}",
        "GATEWAY_DATABASE_MIGRATE"  -> "true"
      ),
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> vipLabel(API_GATEWAY)))
      )),
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      ))
    )
  }

  private[this] def getApiProxy(globals: JsValue): AppSpec = {
    appSpec(API_PROXY).copy(
      env = Map(
        "API_URL" -> s"http://${vipDestination(META)}",
        "SEC_URL" -> s"http://${vipDestination(SECURITY)}"
      ),
      ports = Some(Seq(
        PortSpec(number = 8888, name = "http", labels = Map("VIP_0" -> vipLabel(API_PROXY)))
      )),
      healthChecks = Seq(HealthCheck(
        path = "/service-status",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/service-status",
        portName = "http",
        intervalSeconds = 5
      ))
    )
  }

  private[this] def getUI(globals: JsValue): AppSpec = {
    appSpec(UI).copy(
      env = Map(
        "API_URL" -> s"http://${vipDestination(API_PROXY)}"
      ),
      ports = Some(Seq(
        PortSpec(number = 80, name = "http", labels = Map("VIP_0" -> vipLabel(UI)))
      )),
      healthChecks = Seq(HealthCheck(
        path = "/#/login",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/#/login",
        portName = "http",
        intervalSeconds = 5
      )),
      labels = getVhostLabels(UI)
    )
  }

  private[this] def getRabbit(globals: JsValue): AppSpec = {
    appSpec(RABBIT).copy(
      ports = Some(Seq(
        PortSpec(number = 5672,  name = "service-api", labels = Map("VIP_0" -> vipLabel(RABBIT_AMQP))),
        PortSpec(number = 15672, name = "http-api",    labels = Map("VIP_0" -> vipLabel(RABBIT_HTTP)))
      )),
      healthChecks = Seq(HealthCheck(
        portIndex = 1, protocol = "HTTP", path = "/"
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

  def getMarathonPayload(service: FrameworkService, globals: JsValue): MarathonAppPayload = toMarathonPayload(getAppSpec(service, globals), globals)

  def toMarathonPayload(app: AppSpec, globals: JsValue): MarathonAppPayload = {
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
            p => DockerPortMapping(containerPort = p.number, name = Some(p.name), protocol = "tcp", labels = Some(p.labels))
          ) } else None
        ))
      ),
      labels = app.labels,
      healthChecks = app.healthChecks.map( hc => MarathonHealthCheck(
        path = hc.path,
        protocol = hc.protocol,
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
