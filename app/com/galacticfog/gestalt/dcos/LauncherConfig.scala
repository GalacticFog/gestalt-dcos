package com.galacticfog.gestalt.dcos

import javax.inject.{Inject, Singleton}

import com.galacticfog.gestalt.dcos.HealthCheck.HealthCheckProtocol
import com.galacticfog.gestalt.dcos.LauncherConfig.LaserExecutors._
import com.galacticfog.gestalt.dcos.LauncherConfig.LaserConfig.LaserRuntime
import com.galacticfog.gestalt.dcos.launcher.{LauncherState, LaunchingState}
import com.galacticfog.gestalt.dcos.launcher.States._
import play.api.{Configuration, Logger}
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

@Singleton
class LauncherConfig @Inject()(config: Configuration) {

  val log = Logger(this.getClass)

  import LauncherConfig._
  import LauncherConfig.Services._

  def getString(path: String, default: String): String = config.getString(path).getOrElse(default)

  def getInt(path: String, default: Int): Int = config.getInt(path).getOrElse(default)

  def getBool(path: String, default: Boolean): Boolean = config.getBoolean(path).getOrElse(default)

  def getDouble(path: String, default: Double): Double = config.getDouble(path).getOrElse(default)

  val marathon = MarathonConfig(
    marathonLbUrl = config.getString("marathon.lb-url"),
    marathonLbProto = config.getString("marathon.lb-protocol"),
    appGroup = getString("marathon.app-group", DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/"),
    tld = config.getString("marathon.tld"),
    baseUrl = getString("marathon.url", "http://marathon.mesos:8080"),
    frameworkName = getString("marathon.framework-name", "marathon"),
    clusterName = getString("marathon.cluster-name", "thisdcos"),
    jvmOverheadFactor = getDouble("marathon.jvm-overhead-factor", 1.5),
    networkName = config.getString("marathon.network-name"),
    mesosHealthChecks = getBool("marathon.mesos-health-checks",false),
    networkList = config.getString("marathon.network-list"),
    haproxyGroups = config.getString("marathon.haproxy-groups"),
    sseMaxLineSize = config.getInt("marathon.sseMaxLineSize") getOrElse 524288,
    sseMaxEventSize = config.getInt("marathon.sseMaxEventSize") getOrElse 524288
  )

  val dcos = DCOSConfig(
    secretSupport = config.getBoolean("dcos.secret-support"),
    secretUrl = config.getString("dcos.secret-url"),
    secretStore = config.getString("dcos.secret-store")
  )

  val database = DatabaseConfig(
    provision = getBool("database.provision", true),
    provisionedCpu = config.getDouble("database.provisioned-cpu"),
    provisionedMemory = config.getInt("database.provisioned-memory"),
    provisionedSize = getInt("database.provisioned-size", DatabaseConfig.DEFAULT_DISK),
    numSecondaries = getInt("database.num-secondaries", DatabaseConfig.DEFAULT_NUM_SECONDARIES),
    pgreplToken = getString("database.pgrepl-token", "iw4nn4b3likeu"),
    hostname = getString("database.hostname", marathon.appGroup.replaceAll("/","-") + "-data"),
    port = getInt("database.port", 5432),
    username = getString("database.username", "gestaltdev"),
    password = getString("database.password", "letmein"),
    prefix = getString("database.prefix", "gestalt-")
  )

  val logging = LoggingConfig(
    esClusterName = config.getString("logging.es-cluster-name"),
    esProtocol = config.getString("logging.es-protocol"),
    esHost = config.getString("logging.es-host"),
    esPortTransport = config.getInt("logging.es-port-transport"),
    esPortREST      = config.getInt("logging.es-port-rest"),
    provisionProvider = getBool("logging.provision-provider", false),
    configureLaser = getBool("logging.configure-laser", false)
  )

  val meta = MetaConfig(
    companyName = getString("meta.company-name", MetaConfig.DEFAULT_COMPANY_NAME)
  )

  val security = SecurityConfig(
    username = getString("security.username", "gestalt-admin"),
    password = config.getString("security.password"),
    key = config.getString("security.key"),
    secret = config.getString("security.secret")
  )

  val gestaltFrameworkVersion: Option[String] = config.getString("gestalt-framework-version")

  val acceptAnyCertificate: Option[Boolean] = config.getBoolean("acceptAnyCertificate")

  val dcosAuth = for {
    auth <- config.getString("auth.method")
    if auth == "acs"
    credsStr <- config.getString("auth.acs_service_acct_creds")
    credsJs  <- Try{Json.parse(credsStr)} match {
      case Success(js) => Some(js)
      case Failure(ex) => {
        log.error("Could not parse auth.acs_service_acct_creds/DCOS_ACS_SERVICE_ACCT_CREDS as valid JSON. " +
          "Will disable client authentication, but this will probably not work.")
        None
      }
    }
    acsCreds <- credsJs.validate[DCOSACSServiceAccountCreds](acsServiceAcctFmt) match {
      case JsSuccess(c,_) =>
        Some(c)
      case JsError(errors) =>
        log.error("Failure deserializing auth.acs_service_acct_creds/DCOS_ACS_SERVICE_ACCT_CREDS JSON to acs service account credentials. " +
          errors.foldLeft[String]("[ "){
            case (acc, (path, errors)) => acc + "(%s, %s)".format(path, errors.foldLeft("")(_ + "," + _.message))
          } + "]"
        )
        None
    }
  } yield acsCreds

  val debug = Debug(
    cpu = config.getDouble("debug.cpu"),
    mem = config.getInt("debug.mem")
  )

  val LAUNCH_ORDER: Seq[LauncherState] = {
    val dbs = if (database.provision) {
      Seq(LaunchingDB(0)) ++ (1 to database.numSecondaries).map(LaunchingDB(_))
    } else Seq.empty
    dbs ++ Seq(
      LaunchingRabbit,
      LaunchingSecurity, RetrievingAPIKeys,
      LaunchingMeta, BootstrappingMeta, SyncingMeta, ProvisioningMeta,
      LaunchingUI,
      AllServicesLaunched
    )
  }

  val provisionedServices: Seq[FrameworkService] = LAUNCH_ORDER.collect({case s: LaunchingState => s.targetService})

  protected[this] def vipBase(service: ServiceEndpoint): String = service match {
    case DATA(0) =>
      marathon.appGroup
        .split("/")
        .foldRight("data-primary")(_ + "-" + _)
    case DATA(_) =>
      marathon.appGroup
        .split("/")
        .foldRight("data-secondary")(_ + "-" + _)
    case _ =>
      marathon.appGroup
        .split("/")
        .foldRight(service.name)(_ + "-" + _)
  }

  def vipLabel(service: ServiceEndpoint): String = "/" + vipBase(service) + ":" + service.port

  def vipHostname(service: ServiceEndpoint): String = {
    service match {
      case _ if marathon.networkName.exists(_ != "BRIDGE") =>
        marathon.appGroup.split("/").reverse.foldLeft(service.name)(_ + "-" + _) + ".%s.containerip.dcos.%s.directory".format(marathon.frameworkName, marathon.clusterName)
      case DATA(_) | RABBIT_AMQP =>
        marathon.appGroup.split("/").reverse.foldLeft(service.name)(_ + "." + _) + "." + marathon.frameworkName + ".mesos"
      case _ =>
        vipBase(service) + s".${marathon.frameworkName}.l4lb.${marathon.clusterName}.directory"
    }
  }

  def dockerImage(service: Dockerable): String = {
    val name = service match {
      case DATA(_) => "data"
      case _ => service.name
    }
    config
      .getString(s"containers.${name}")
      .orElse(gestaltFrameworkVersion.map(
        ensVer => service match {
          case DATA(_) =>
            s"galacticfog/postgres_repl:release-${ensVer}"
          case RABBIT | KONG =>
            s"galacticfog/${name}:release-${ensVer}"
          case _ =>
            s"galacticfog/gestalt-${name}:release-${ensVer}"
        }
      ))
      .getOrElse(defaultDockerImages(service))
  }

  val disabledRuntimes = Map(
    EXECUTOR_NASHORN -> getBool("laser.enable-js-runtime", true),
    EXECUTOR_JVM     -> getBool("laser.enable-jvm-runtime", true),
    EXECUTOR_DOTNET  -> getBool("laser.enable-dotnet-runtime", true),
    EXECUTOR_RUBY    -> getBool("laser.enable-ruby-runtime", true),
    EXECUTOR_BASH    -> getBool("laser.enable-bash-runtime", true),
    EXECUTOR_PYTHON  -> getBool("laser.enable-python-runtime", true),
    EXECUTOR_GOLANG  -> getBool("laser.enable-golang-runtime", true),
    EXECUTOR_NODEJS  -> getBool("laser.enable-nodejs-runtime", true)
  ).collect({
    case (exec,false) => exec
  })

  val laser = LaserConfig(
    scaleDownTimeout = config.getInt("laser.scale-down-timeout"),
    enabledRuntimes = (LaserConfig.KNOWN_LASER_RUNTIMES -- disabledRuntimes).map({
      case (e,r) => r.copy(
        image = dockerImage(e)
      )
    }).toSeq,
    maxCoolConnectionTime = config.getInt("laser.max-cool-connection-time") getOrElse LaserConfig.Defaults.MAX_COOL_CONNECTION_TIME,
    executorHeartbeatTimeout = config.getInt("laser.executor-heartbeat-timeout") getOrElse LaserConfig.Defaults.EXECUTOR_HEARTBEAT_TIMEOUT,
    executorHeartbeatPeriod = config.getInt("laser.executor-heartbeat-period") getOrElse LaserConfig.Defaults.EXECUTOR_HEARTBEAT_MILLIS,
    defaultExecutorCpu = config.getDouble("laser.default-executor-cpu") getOrElse LaserConfig.Defaults.DEFAULT_EXECUTOR_CPU,
    defaultExecutorMem = config.getInt("laser.default-executor-mem") getOrElse LaserConfig.Defaults.DEFAULT_EXECUTOR_MEM,
    serviceHostOverride = config.getString("laser.service-host-override"),
    servicePortOverride = config.getInt("laser.service-port-override")
  )

  private[this] val servicesWithPrefixes: Map[Dockerable,String] = provisionedServices.collect({ case d @ DATA(i) => d -> s"DATA_${i}" }).toMap ++ Map(
    RABBIT -> "RABBIT",
    SECURITY -> "SECURITY",
    META -> "META",
    UI -> "UI",
    KONG -> "KONG",
    LASER -> "LASER",
    POLICY -> "POLICY",
    API_GATEWAY -> "API_GATEWAY",
    LOG -> "LOG",
    EXECUTOR_DOTNET  -> "EXECUTOR_DOTNET",
    EXECUTOR_NASHORN -> "EXECUTOR_NASHORN",
    EXECUTOR_NODEJS  -> "EXECUTOR_NODEJS",
    EXECUTOR_JVM     -> "EXECUTOR_JVM",
    EXECUTOR_PYTHON  -> "EXECUTOR_PYTHON",
    EXECUTOR_GOLANG  -> "EXECUTOR_GOLANG",
    EXECUTOR_RUBY    -> "EXECUTOR_RUBY",
    EXECUTOR_BASH    -> "EXECUTOR_BASH"
  )

  val extraEnv: Map[Dockerable,Map[String,String]] = {
    sys.env.flatMap({
      case (k,v) if !wellKnownEnvVars.contains(k) =>
        servicesWithPrefixes.find({case (_,pfx) => k.startsWith(pfx + "_")}) match {
          case Some((svc,pfx)) => Some((svc,k.stripPrefix(pfx + "_"),v))
          case _=> None
        }
      case _ => None
    }).groupBy(_._1).map({
      case (svc, env) => (svc,env.map({case (_,k,v) => (k,v)}).toMap)
    }).withDefaultValue(Map.empty[String,String])
  }

  val resources = LauncherConfig.Resources(
    cpu = servicesWithPrefixes.flatMap({
      case (svc,prefix) => sys.env.get("CPU_" + prefix).flatMap(d => Try{d.toDouble}.toOption).map(svc -> _)
    }),
    mem = servicesWithPrefixes.flatMap({
      case (svc,prefix) => sys.env.get("MEM_" + prefix).flatMap(d => Try{d.toInt}.toOption).map(svc -> _)
    })
  )

  def apply(healthCheck: HealthCheckProtocol): HealthCheckProtocol = {
    import HealthCheck._
    healthCheck match {
      case MARATHON_HTTP  | MESOS_HTTP  => if (marathon.mesosHealthChecks) MESOS_HTTP  else MARATHON_HTTP
      case MARATHON_HTTPS | MESOS_HTTPS => if (marathon.mesosHealthChecks) MESOS_HTTPS else MARATHON_HTTPS
      case MARATHON_TCP   | MESOS_TCP   => if (marathon.mesosHealthChecks) MESOS_TCP   else MARATHON_TCP
      case COMMAND => COMMAND
    }
  }

}

object LauncherConfig {

  val DEFAULT_APP_GROUP = "gestalt-framework"

  val MARATHON_RECONNECT_DELAY: FiniteDuration = 10 seconds

  val EXTERNAL_API_CALL_TIMEOUT: FiniteDuration = 30 seconds

  val EXTERNAL_API_RETRY_INTERVAL: FiniteDuration = 5 seconds

  sealed trait Dockerable {
    def name: String
  }

  sealed trait FrameworkService extends Dockerable {
    def cpu: Double
    def mem: Int
  }

  sealed trait ServiceEndpoint {
    def name: String
    def port: Int
  }

  object Services {
    case object RABBIT           extends FrameworkService                      with Dockerable {val name = "rabbit";         val cpu = 2.00; val mem = 2048;}
    case class  DATA(index: Int) extends FrameworkService with ServiceEndpoint with Dockerable {val name = s"data-${index}"; val cpu = 2.00; val mem = 4096; val port = 5432}
    case object SECURITY         extends FrameworkService with ServiceEndpoint with Dockerable {val name = "security";       val cpu = 2.00; val mem = 2048; val port = 9455}
    case object META             extends FrameworkService with ServiceEndpoint with Dockerable {val name = "meta";           val cpu = 2.00; val mem = 2048; val port = 14374}
    case object UI               extends FrameworkService with ServiceEndpoint with Dockerable {val name = "ui-react";       val cpu = 0.50; val mem =  256; val port = 80}

    case object KONG                                                        extends Dockerable {val name = "kong";           val cpu = 2.00; val mem = 1024;}
    case object LASER                                  extends ServiceEndpoint with Dockerable {val name = "laser";          val cpu = 2.00; val mem = 2048; val port = 1111}
    case object POLICY                                 extends ServiceEndpoint with Dockerable {val name = "policy";         val cpu = 2.00; val mem = 2048; val port = 9999}
    case object API_GATEWAY                            extends ServiceEndpoint with Dockerable {val name = "api-gateway";    val cpu = 2.00; val mem = 2048; val port = 6473}
    case object LOG                                    extends ServiceEndpoint with Dockerable {val name = "log";            val cpu = 2.00; val mem = 2048; val port = 9000}

    case object RABBIT_AMQP                            extends ServiceEndpoint                 {val name: String = RABBIT.name;                              val port = 5672}
    case object RABBIT_HTTP                            extends ServiceEndpoint                 {val name: String = RABBIT.name;                              val port = 15672}
    case object KONG_GATEWAY                           extends ServiceEndpoint                 {val name: String = KONG.name;                                val port = 8000}
    case object KONG_SERVICE                           extends ServiceEndpoint                 {val name: String = KONG.name;                                val port = 8001}

    case object DataFromName {
      private[this] val dataRegex = "data-([0-9]+)".r
      def unapply(serviceName: String): Option[DATA] = serviceName match {
        case dataRegex(index) => Try{DATA(index.toInt)}.toOption
        case _ => None
      }
    }

    val allServices: Seq[FrameworkService] = Seq( RABBIT, SECURITY, META, UI )

    def fromName(serviceName: String): Option[FrameworkService] = allServices.find(_.name == serviceName) orElse DataFromName.unapply(serviceName)
  }

  sealed trait WellKnownLaserExecutor extends Dockerable

  object LaserExecutors {
    case object EXECUTOR_DOTNET  extends WellKnownLaserExecutor {val name = "laser-executor-dotnet"}
    case object EXECUTOR_NASHORN extends WellKnownLaserExecutor {val name = "laser-executor-js"}
    case object EXECUTOR_NODEJS  extends WellKnownLaserExecutor {val name = "laser-executor-nodejs"}
    case object EXECUTOR_JVM     extends WellKnownLaserExecutor {val name = "laser-executor-jvm"}
    case object EXECUTOR_PYTHON  extends WellKnownLaserExecutor {val name = "laser-executor-python"}
    case object EXECUTOR_GOLANG  extends WellKnownLaserExecutor {val name = "laser-executor-golang"}
    case object EXECUTOR_RUBY    extends WellKnownLaserExecutor {val name = "laser-executor-ruby"}
    case object EXECUTOR_BASH    extends WellKnownLaserExecutor {val name = "laser-executor-bash"}
  }

  def defaultDockerImages(service: Dockerable): String = service match {
    case Services.DATA(_)             => s"galacticfog/postgres_repl:release-${BuildInfo.version}"
    case Services.RABBIT              => s"galacticfog/rabbit:release-${BuildInfo.version}"
    case Services.KONG                => s"galacticfog/kong:release-${BuildInfo.version}"
    case Services.SECURITY            => s"galacticfog/gestalt-security:release-${BuildInfo.version}"
    case Services.META                => s"galacticfog/gestalt-meta:release-${BuildInfo.version}"
    case Services.POLICY              => s"galacticfog/gestalt-policy:release-${BuildInfo.version}"
    case Services.LASER               => s"galacticfog/gestalt-laser:release-${BuildInfo.version}"
    case Services.LOG                 => s"galacticfog/gestalt-log:release-${BuildInfo.version}"
    case Services.API_GATEWAY         => s"galacticfog/gestalt-api-gateway:release-${BuildInfo.version}"
    case Services.UI                  => s"galacticfog/gestalt-ui-react:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_DOTNET    => s"galacticfog/gestalt-laser-executor-dotnet:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_NODEJS    => s"galacticfog/gestalt-laser-executor-nodejs:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_NASHORN   => s"galacticfog/gestalt-laser-executor-js:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_JVM       => s"galacticfog/gestalt-laser-executor-jvm:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_PYTHON    => s"galacticfog/gestalt-laser-executor-python:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_GOLANG    => s"galacticfog/gestalt-laser-executor-golang:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_RUBY      => s"galacticfog/gestalt-laser-executor-ruby:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_BASH      => s"galacticfog/gestalt-laser-executor-bash:release-${BuildInfo.version}"
  }

  case class DatabaseConfig( provision: Boolean,
                             provisionedSize: Int,
                             provisionedCpu: Option[Double],
                             provisionedMemory: Option[Int],
                             numSecondaries: Int,
                             pgreplToken: String,
                             hostname: String,
                             port: Int,
                             username: String,
                             password: String,
                             prefix: String )

  case object DatabaseConfig {
    val DEFAULT_DISK: Int = 100

    val DEFAULT_NUM_SECONDARIES = 0
    val DEFAULT_KILL_GRACE_PERIOD = 300
  }

  case class Debug( cpu: Option[Double], mem: Option[Int] )

  case class MarathonConfig( marathonLbUrl: Option[String],
                             marathonLbProto: Option[String],
                             appGroup: String,
                             tld: Option[String],
                             baseUrl: String,
                             frameworkName: String,
                             clusterName: String,
                             jvmOverheadFactor: Double,
                             networkName: Option[String],
                             mesosHealthChecks: Boolean,
                             networkList: Option[String],
                             haproxyGroups: Option[String],
                             sseMaxLineSize: Int,
                             sseMaxEventSize: Int
                           )

  case class DCOSConfig( secretSupport: Option[Boolean],
                         secretUrl: Option[String],
                         secretStore: Option[String]
                       )

  case class SecurityConfig( username: String,
                             password: Option[String],
                             key: Option[String],
                             secret: Option[String] )

  case class LoggingConfig(esClusterName: Option[String],
                           esProtocol: Option[String],
                           esHost: Option[String],
                           esPortTransport: Option[Int],
                           esPortREST: Option[Int],
                           provisionProvider: Boolean,
                           configureLaser: Boolean )

  case class LaserConfig( scaleDownTimeout: Option[Int],
                          enabledRuntimes: Seq[LaserRuntime],
                          maxCoolConnectionTime: Int,
                          executorHeartbeatTimeout: Int,
                          executorHeartbeatPeriod: Int,
                          defaultExecutorCpu: Double,
                          defaultExecutorMem: Int,
                          serviceHostOverride: Option[String],
                          servicePortOverride: Option[Int] )

  implicit val acsServiceAcctFmt = Json.format[DCOSACSServiceAccountCreds]

  case class DCOSACSServiceAccountCreds( login_endpoint : String,
                                         uid : String,
                                         private_key : String,
                                         scheme: String )

  case object LaserConfig {
    case object Defaults {
      val DEFAULT_EXECUTOR_MEM = 1024

      val DEFAULT_EXECUTOR_CPU = 1.0

      val EXECUTOR_HEARTBEAT_TIMEOUT = 5000

      val EXECUTOR_HEARTBEAT_MILLIS = 10000

      val MAX_COOL_CONNECTION_TIME: Int = 120
    }

    case class LaserRuntime(name: String, runtime: String, image: String, cmd: String, metaType: String, laserExecutor: Option[WellKnownLaserExecutor] = None)

    val KNOWN_LASER_RUNTIMES: Map[WellKnownLaserExecutor, LaserRuntime] = Map(
      EXECUTOR_NODEJS   -> LaserRuntime("nodejs-executor",  "nodejs",        "", "bin/gestalt-laser-executor-nodejs", "NodeJS", Some(EXECUTOR_NODEJS)),
      EXECUTOR_NASHORN  -> LaserRuntime("nashorn-executor", "nashorn",       "", "bin/gestalt-laser-executor-js",     "Nashorn", Some(EXECUTOR_NASHORN)),
      EXECUTOR_JVM      -> LaserRuntime("jvm-executor",     "java;scala",    "", "bin/gestalt-laser-executor-jvm"   , "Java", Some(EXECUTOR_JVM)),
      EXECUTOR_DOTNET   -> LaserRuntime("dotnet-executor",  "csharp;dotnet", "", "bin/gestalt-laser-executor-dotnet", "CSharp", Some(EXECUTOR_DOTNET)),
      EXECUTOR_PYTHON   -> LaserRuntime("python-executor",  "python",        "", "bin/gestalt-laser-executor-python", "Python", Some(EXECUTOR_PYTHON)),
      EXECUTOR_RUBY     -> LaserRuntime("ruby-executor",    "ruby",          "", "bin/gestalt-laser-executor-ruby"  , "Ruby", Some(EXECUTOR_RUBY)),
      EXECUTOR_BASH     -> LaserRuntime("bash-executor",    "bash",          "", "bin/gestalt-laser-executor-bash"  , "Bash", Some(EXECUTOR_BASH)),
      EXECUTOR_GOLANG   -> LaserRuntime("golang-executor",  "golang",        "", "bin/gestalt-laser-executor-golang", "GoLang", Some(EXECUTOR_GOLANG))
    )
  }

  case class MetaConfig( companyName: String )

  object MetaConfig {
    val DEFAULT_COMPANY_NAME = "A Galactic Fog Customer"

    val SETUP_LAMBDA_URL = "https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.5/js_lambda/demo-setup.js"
    val TDOWN_LAMBDA_URL = "https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.5/js_lambda/demo-teardown.js"
  }

  val wellKnownEnvVars = Set(
    "LASER_EXECUTOR_NODEJS_IMG",
    "LASER_EXECUTOR_JS_IMG",
    "LASER_EXECUTOR_JVM_IMG",
    "LASER_EXECUTOR_DOTNET_IMG",
    "LASER_EXECUTOR_PYTHON_IMG",
    "LASER_EXECUTOR_RUBY_IMG",
    "LASER_EXECUTOR_BASH_IMG",
    "LASER_EXECUTOR_GOLANG_IMG",

    "LASER_ENABLE_NODEJS_RUNTIME",
    "LASER_ENABLE_JS_RUNTIME",
    "LASER_ENABLE_JVM_RUNTIME",
    "LASER_ENABLE_DOTNET_RUNTIME",
    "LASER_ENABLE_RUBY_RUNTIME",
    "LASER_ENABLE_BASH_RUNTIME",
    "LASER_ENABLE_PYTHON_RUNTIME",
    "LASER_ENABLE_GOLANG_RUNTIME",

    "LASER_SCALE_DOWN_TIMEOUT",
    "LASER_MAX_CONN_TIME",
    "LASER_EXECUTOR_HEARTBEAT_TIMEOUT",
    "LASER_EXECUTOR_HEARTBEAT_PERIOD",
    "LASER_SERVICE_HOST_OVERRIDE",
    "LASER_SERVICE_PORT_OVERRIDE",
    "LASER_DEFAULT_EXECUTOR_MEM",
    "LASER_DEFAULT_EXECUTOR_CPU",

    "LOGGING_ES_CLUSTER_NAME",
    "LOGGING_ES_HOST",
    "LOGGING_ES_PORT_TRANSPORT",
    "LOGGING_ES_PORT_REST",
    "LOGGING_ES_PROTOCOL",
    "LOGGING_PROVISION_PROVIDER",
    "LOGGING_CONFIGURE_LASER"
  )

  case class Resources(cpu: Map[Dockerable,Double], mem: Map[Dockerable,Int])

}
