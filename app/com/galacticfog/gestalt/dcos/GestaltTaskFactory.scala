package com.galacticfog.gestalt.dcos

import java.util.UUID

import com.galacticfog.gestalt.dcos.marathon._
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.libs.json.JsValue

case class PortSpec(number: Int, name: String, labels: Map[String,String])
case class HealthCheck(portIndex: Int, protocol: String, path: String)

case class AppSpec(name: String,
                   image: String,
                   network: Protos.ContainerInfo.DockerInfo.Network,
                   ports: Option[Seq[PortSpec]] = None,
                   dockerParameters: Seq[KeyValuePair] = Seq.empty,
                   cpus: Double,
                   mem: Int,
                   env: Map[String,String] = Map.empty,
                   labels: Map[String,String ] = Map.empty,
                   args: Option[Seq[String]] = None,
                   cmd: Option[String] = None,
                   healthChecks: Seq[HealthCheck] = Seq.empty,
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

class GestaltTaskFactory {

  import GestaltTaskFactory._

  def allServices = Seq("data","security","rabbit","meta","api-proxy","ui","api-gateway","lambda","kong","policy")

  def getAppSpec(name: String, globals: JsValue): AppSpec = {
    name match {
      case "data" => getData(globals)
      case "rabbit" => getRabbit(globals)
      case "security" => getSecurity(globals)
      case "meta" => getMeta(globals)
      case "api-proxy" => getApiProxy(globals)
      case "ui" => getUI(globals)
      case "kong" => getKong(globals)
      case "api-gateway" => getApiGateway(globals)
      case "lambda" => getLambda(globals)
      case "policy" => getPolicy(globals)
    }
  }

  private[this] def getData(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    AppSpec(
      name = "data",
      env = Map(
        "DB_USER" -> dbConfig.username,
        "DB_PASS" -> dbConfig.password
      ),
      image = "galacticfog.artifactoryonline.com/gestalt-data:latest",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 5432, name = "sql", labels = Map("VIP_0" -> "10.99.99.10:5432")))),
      cpus = 0.50,
      mem = 256,
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = "TCP", path = ""
      ))
    )
  }

  private[this] def getSecurity(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = (globals \ "marathon" \ "tld").asOpt[String] match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"security.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "security",
      env = Map(
        "DATABASE_HOSTNAME" -> dbConfig.hostname,
        "DATABASE_PORT" -> dbConfig.port.toString,
        "DATABASE_NAME" -> (dbConfig.prefix + "security"),
        "DATABASE_USERNAME" -> dbConfig.username,
        "DATABASE_PASSWORD" -> dbConfig.password,
        "OAUTH_RATE_LIMITING_AMOUNT" -> (secConfig \ "oauth" \ "rateLimitingAmount").asOpt[Int].map(_.toString).getOrElse("100"),
        "OAUTH_RATE_LIMITING_PERIOD" -> (secConfig \ "oauth" \ "rateLimitingPeriod").asOpt[Int].map(_.toString).getOrElse("1")
      ),
      args = Some(Seq("-J-Xmx512m")),
      image = "galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> "10.99.99.20:80")))),
      cpus = 1.0,
      mem = 768,
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
      labels = labels
    )
  }

  private[this] def getMeta(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val tld = (globals \ "marathon" \ "tld").asOpt[String]
    val labels = tld match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"meta.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "meta",
      env = Map(
        "DATABASE_HOSTNAME" -> dbConfig.hostname,
        "DATABASE_PORT" -> dbConfig.port.toString,
        "DATABASE_NAME" -> (dbConfig.prefix + "meta"),
        "DATABASE_USERNAME" -> dbConfig.username,
        "DATABASE_PASSWORD" -> dbConfig.password,
        //
        "GESTALT_APIGATEWAY" -> "http://10.99.99.21:80",
        "GESTALT_LAMBDA" -> "http://10.99.99.22:80",
        "GESTALT_MARATHON_PROVIDER" -> "",
        //
        "GESTALT_ENV" -> "appliance; DEV",
        "GESTALT_ID" -> "bd96d05a-7065-4fa2-bea2-98beebe8ebe4",
        "GESTALT_META" -> s"https://meta.${tld}:443",
        "GESTALT_NODE_ID" -> "0",
        "GESTALT_ORG" -> "com.galacticfog.test",
        "GESTALT_SECRET" -> "secret",
        "GESTALT_VERSION" -> "1.0",
        //
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> "10.99.99.20",
        "GESTALT_SECURITY_PORT" -> "80",
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "RABBIT_EXCHANGE" -> "test-exchange",
        "RABBIT_HOST" -> "10.99.99.11",
        "RABBIT_PORT" -> "5672",
        "RABBIT_ROUTE" -> "policy"
      ),
      args = Some(Seq("-J-Xmx512m")),
      image = "galacticfog.artifactoryonline.com/gestalt-meta:0.3.1-SNAPSHOT-29cfc409",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> "10.99.99.23:80")))),
      cpus = 2.0,
      mem = 768,
      healthChecks = Seq.empty /* Seq(HealthCheck(portIndex = 0, protocol = "HTTP", path = "/health")) */,
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/info",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200,401,403),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = labels
    )
  }

  private[this] def getKong(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val labels = (globals \ "marathon" \ "tld").asOpt[String] match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"gateway-kong.${tld}",
        "HAPROXY_1_VHOST" -> s"service-kong.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "kong",
      cmd = Some(s"echo 'BEFORE' && cat /etc/kong/kong.yml && echo -en 'database: postgres\\npostgres:\\n  host: ${dbConfig.hostname}\\n  port: ${dbConfig.port}\\n  user: ${dbConfig.username}\\n  password: ${dbConfig.password}\\n  database: ${dbConfig.prefix}kong\\n' > add && cat /etc/kong/kong.yml | sed -e '/postgres:/,+6d' | sed -e '1r add' | sed -e '1d' > /tmp/txt && mv /tmp/txt /etc/kong/kong.yml && cat /etc/kong/kong.yml && kong start && tail -f /usr/local/kong/logs/error.log"),
      env = Map.empty,
      image = "mashape/kong:0.8.0",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 8000, name = "gateway-api", labels = Map("VIP_0" -> "10.99.99.12:80")),
        PortSpec(number = 8001, name = "service-api", labels = Map("VIP_0" -> "10.99.99.13:80"))
      )),
      cpus = 0.25,
      mem = 128,
      healthChecks = Seq.empty,
      readinessCheck = None,
      labels = labels
    )
  }

  private[this] def getPolicy(globals: JsValue): AppSpec = ???

  private[this] def getLambda(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = (globals \ "marathon" \ "tld").asOpt[String] match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"lambda.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "lambda",
      env = Map(
        "LAMBDA_DATABASE_NAME" -> s"${dbConfig.prefix}lambda",
        "LAMBDA_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "LAMBDA_DATABASE_PORT" -> s"${dbConfig.port}",
        "LAMBDA_DATABASE_USER" -> s"${dbConfig.username}",
        "LAMBDA_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "LAMBDA_FLYWAY_MIGRATE" -> "true",
        //
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> "10.99.99.20",
        "GESTALT_SECURITY_PORT" -> "80",
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "META_PROTOCOL" -> "http",
        "META_HOSTNAME" -> "10.99.99.23",
        "META_PORT" -> "80",
        "META_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "MESOS_ROLE" -> "*",
        "MESOS_NATIVE_JAVA_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_NATIVE_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_MASTER_CONNECTION" -> "zk://master.mesos:2181/mesos",
        //
        "NEW_RELIC_LICENSE_KEY" -> "64300aae4a006efc6fa13ab9f88386f186707003",
        "CACHE_EXPIRE_SECONDS" -> "900",
        "SCHEDULER_NAME" -> "gestalt-lambda-scheduler",
        "MAX_LAMBDAS_PER_OFFER" -> "6",
        "OFFER_TTL" -> "5"
      ),
      image = "galacticfog.artifactoryonline.com/gestalt-lambda:1.0.3-SNAPSHOT-2de5aaf0",
      network = ContainerInfo.DockerInfo.Network.HOST,
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> "10.99.99.22:80"))
      )),
      cpus = 2.0,
      cmd = Some("./bin/gestalt-lambda -Dhttp.port=$PORT0 -Dlogger.file=/opt/docker/conf/logback.xml -J-Xmx1024m"),
      mem = 1536,
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
      labels = labels
    )
  }

  private[this] def getApiGateway(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = (globals \ "marathon" \ "tld").asOpt[String] match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"apigateway.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "api-gateway",
      cmd = None,
      env = Map(
        "GATEWAY_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "GATEWAY_DATABASE_MIGRATE" -> "true",
        "GATEWAY_DATABASE_NAME" -> s"${dbConfig.prefix}apigateway",
        "GATEWAY_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "GATEWAY_DATABASE_PORT" -> s"${dbConfig.port}",
        "GATEWAY_DATABASE_USER" -> s"${dbConfig.username}",
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> "10.99.99.20",
        "GESTALT_SECURITY_PORT" -> "80",
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        "OVERRIDE_UPSTREAM_PROTOCOL" -> "http"
      ),
      image = "galacticfog.artifactoryonline.com/gestalt-api-gateway:1.0.3-SNAPSHOT-ebf0f14b",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> "10.99.99.21:80"))
      )),
      cpus = 0.5,
      mem = 1536,
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
      labels = labels
    )
  }

  private[this] def getApiProxy(globals: JsValue): AppSpec = {
    AppSpec(
      name = "api-proxy",
      env = Map(
        "API_URL" -> "http://10.99.99.23:80",
        "SEC_URL" -> "http://10.99.99.20:80"
      ),
      image = "galacticfog.artifactoryonline.com/gestalt-api-proxy:0.5.8-c03ffa57",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 8888, name = "http", labels = Map("VIP_0" -> "10.99.99.24:80"))
      )),
      cpus = 0.25,
      mem = 128,
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
    val labels = (globals \ "marathon" \ "tld").asOpt[String] match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"ui.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
    AppSpec(
      name = "ui",
      env = Map(
        "API_URL" -> "http://10.99.99.24"
      ),
      image = "galacticfog.artifactoryonline.com/gestalt-ui:0.7.9-507bbf18",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 80, name = "http", labels = Map("VIP_0" -> "10.99.99.25:80"))
      )),
      cpus = 0.25,
      mem = 128,
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
      labels = labels
    )
  }

  private[this] def getRabbit(globals: JsValue): AppSpec = {
    AppSpec(
      name = "rabbit",
      env = Map.empty,
      image = "rabbitmq:3-management",
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 5672,  name = "service-api", labels = Map("VIP_0" -> "10.99.99.11:5672")),
        PortSpec(number = 15672, name = "http-api",    labels = Map("VIP_0" -> "10.99.99.11:80"))
      )),
      cpus = 0.50,
      mem = 256,
      healthChecks = Seq(HealthCheck(
        portIndex = 1, protocol = "HTTP", path = "/"
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = Map.empty
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

  def getMarathonPayload(name: String, globals: JsValue): MarathonAppPayload = toMarathonPayload(getAppSpec(name, globals), globals)

  def toMarathonPayload(app: AppSpec, globals: JsValue): MarathonAppPayload = {
    val prefix = (globals \ "marathon" \ "appGroup").asOpt[String] getOrElse DEFAULT_APP_GROUP
    val cleanPrefix = "/" + prefix.stripPrefix("/").stripSuffix("/") + "/"
    val isBridged = app.network.getValueDescriptor.getName == "BRIDGE"
    MarathonAppPayload(
      id = cleanPrefix + app.name,
      args = app.args,
      env = app.env,
      instances = 1,
      cpus = app.cpus,
      cmd = app.cmd,
      mem = app.mem,
      disk = 0,
      requirePorts = true,
      container = MarathonContainerInfo(
        containerType = "DOCKER",
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
        intervalSeconds = 60,
        timeoutSeconds = 20,
        maxConsecutiveFailures = 3
      ) ),
      readinessCheck = app.readinessCheck,
      portDefinitions = if (!isBridged) app.ports.map {_.map(
        p => PortDefinition(port = p.number, protocol = "tcp", name = Some(p.name), labels = Some(p.labels))
      )} else None
    )
  }

  def toTaskInfo(app: AppSpec, offer: Offer): TaskInfo = {
    val commandInfo = CommandInfo.newBuilder()
      .setShell(false)
      .setEnvironment(app.env)

    val containerInfo = Protos.ContainerInfo.newBuilder
      .setType( Protos.ContainerInfo.Type.DOCKER )
      .setDocker( ContainerInfo.DockerInfo.newBuilder
        .setImage(app.image)
        .setForcePullImage(true)
        .setNetwork(app.network)
        .build
      )

    TaskInfo.newBuilder()
      .setName( app.name )
      .addResources(
        Resource.newBuilder()
          .setName("cpus")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.cpus ))
      )
      .addResources(
        Resource.newBuilder()
          .setName("mem")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.mem ))
      )
      .setCommand(commandInfo)
      .setContainer(containerInfo)
      .setSlaveId(offer.getSlaveId)
      .setTaskId(
        Protos.TaskID.newBuilder().setValue(app.name + "-" + UUID.randomUUID.toString)
      )
      .build()
  }

}

case object GestaltTaskFactory {
  val DEFAULT_APP_GROUP = "gestalt-framework"
}
