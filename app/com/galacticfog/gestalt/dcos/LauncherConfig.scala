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
    appGroup = getString("marathon.app-group", DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/"),
    tld = config.getString("marathon.tld"),
    baseUrl = getString("marathon.url", "http://marathon.mesos:8080"),
    frameworkName = getString("marathon.framework-name", "marathon"),
    clusterName = getString("marathon.cluster-name", "thisdcos"),
    jvmOverheadFactor = getDouble("marathon.jvm-overhead-factor", 2.0),
    networkName = config.getString("marathon.network-name"),
    mesosHealthChecks = config.getBoolean("marathon.mesos-health-checks")
  )

  val database = DatabaseConfig(
    provision = getBool("database.provision", true),
    provisionedSize = getInt("database.provisioned-size", 100),
    numSecondaries = getInt("database.num-secondaries", DatabaseConfig.DEFAULT_NUM_SECONDARIES),
    pgreplToken = getString("database.pgrepl-token", "iw4nn4b3likeu"),
    hostname = getString("database.hostname", marathon.appGroup.replaceAll("/","-") + "-data"),
    port = getInt("database.port", 5432),
    username = getString("database.username", "gestaltdev"),
    password = getString("database.password", "letmein"),
    prefix = getString("database.prefix", "gestalt-")
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
    EXECUTOR_JS     -> getBool("laser.enable-js-runtime", true),
    EXECUTOR_JVM    -> getBool("laser.enable-jvm-runtime", true),
    EXECUTOR_DOTNET -> getBool("laser.enable-dotnet-runtime", true),
    EXECUTOR_RUBY   -> getBool("laser.enable-ruby-runtime", true),
    EXECUTOR_PYTHON -> getBool("laser.enable-python-runtime", true),
    EXECUTOR_GOLANG -> getBool("laser.enable-golang-runtime", true)
  ).collect({
    case (exec,false) => exec
  })

  val laser = LaserConfig(
    minCoolExecutors = getInt("laser.min-cool-executors", LaserConfig.DEFAULT_MIN_COOL_EXECS),
    scaleDownTimeout = getInt("laser.scale-down-timeout", LaserConfig.DEFAULT_SCALE_DOWN_TIMEOUT),
    minPortRange     = getInt("laser.min-port-range", LaserConfig.DEFAULT_MIN_PORT_RANGE),
    maxPortRange     = getInt("laser.max-port-range", LaserConfig.DEFAULT_MAX_PORT_RANGE),
    enabledRuntimes = (LaserConfig.KNOWN_LASER_RUNTIMES -- disabledRuntimes).map({
      case (e,r) => r.copy(
        image = dockerImage(e)
      )
    }).toSeq,
    ethernetPort = config.getString("laser.ethernet-port"),
    advertiseHost = config.getString("laser.advertise-hostname")
  )

  def apply(healthCheck: HealthCheckProtocol): HealthCheckProtocol = {
    import HealthCheck._
    healthCheck match {
      case MARATHON_HTTP  | MESOS_HTTP  => if (marathon.mesosHealthChecks.contains(true)) MESOS_HTTP  else MARATHON_HTTP
      case MARATHON_HTTPS | MESOS_HTTPS => if (marathon.mesosHealthChecks.contains(true)) MESOS_HTTPS else MARATHON_HTTPS
      case MARATHON_TCP   | MESOS_TCP   => if (marathon.mesosHealthChecks.contains(true)) MESOS_TCP   else MARATHON_TCP
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
    case object RABBIT           extends FrameworkService                      with Dockerable {val name = "rabbit";         val cpu = 0.50; val mem = 256;}
    case class  DATA(index: Int) extends FrameworkService with ServiceEndpoint with Dockerable {val name = s"data-${index}"; val cpu = 2.00; val mem = 4096; val port = 5432}
    case object SECURITY         extends FrameworkService with ServiceEndpoint with Dockerable {val name = "security";       val cpu = 0.50; val mem = 1536; val port = 9455}
    case object META             extends FrameworkService with ServiceEndpoint with Dockerable {val name = "meta";           val cpu = 2.00; val mem = 3072; val port = 14374}
    case object UI               extends FrameworkService with ServiceEndpoint with Dockerable {val name = "ui-react";       val cpu = 0.25; val mem = 128;  val port = 80}

    case object KONG                                                        extends Dockerable {val name = "kong";           val cpu = 0.50; val mem = 128;}
    case object LASER                                  extends ServiceEndpoint with Dockerable {val name = "laser";          val cpu = 0.50; val mem = 1536; val port = 1111}
    case object POLICY                                 extends ServiceEndpoint with Dockerable {val name = "policy";         val cpu = 0.25; val mem = 1024; val port = 9999}
    case object API_GATEWAY                            extends ServiceEndpoint with Dockerable {val name = "api-gateway";    val cpu = 0.25; val mem = 1024; val port = 6473}

    case object RABBIT_AMQP      extends ServiceEndpoint                        {val name: String = RABBIT.name;                                   val port = 5672}
    case object RABBIT_HTTP      extends ServiceEndpoint                        {val name: String = RABBIT.name;                                   val port = 15672}
    case object KONG_GATEWAY     extends ServiceEndpoint                        {val name: String = KONG.name;                                     val port = 8000}
    case object KONG_SERVICE     extends ServiceEndpoint                        {val name: String = KONG.name;                                     val port = 8001}

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
    case object EXECUTOR_DOTNET extends WellKnownLaserExecutor {val name = "laser-executor-dotnet"}
    case object EXECUTOR_JS     extends WellKnownLaserExecutor {val name = "laser-executor-js"}
    case object EXECUTOR_JVM    extends WellKnownLaserExecutor {val name = "laser-executor-jvm"}
    case object EXECUTOR_PYTHON extends WellKnownLaserExecutor {val name = "laser-executor-python"}
    case object EXECUTOR_GOLANG extends WellKnownLaserExecutor {val name = "laser-executor-golang"}
    case object EXECUTOR_RUBY   extends WellKnownLaserExecutor {val name = "laser-executor-ruby"}
  }

  def defaultDockerImages(service: Dockerable): String = service match {
    case Services.DATA(_)             => s"galacticfog/postgres_repl:release-${BuildInfo.version}"
    case Services.RABBIT              => s"galacticfog/rabbit:release-${BuildInfo.version}"
    case Services.KONG                => s"galacticfog/kong:release-${BuildInfo.version}"
    case Services.SECURITY            => s"galacticfog/gestalt-security:release-${BuildInfo.version}"
    case Services.META                => s"galacticfog/gestalt-meta:release-${BuildInfo.version}"
    case Services.POLICY              => s"galacticfog/gestalt-policy:release-${BuildInfo.version}"
    case Services.LASER               => s"galacticfog/gestalt-laser:release-${BuildInfo.version}"
    case Services.API_GATEWAY         => s"galacticfog/gestalt-api-gateway:release-${BuildInfo.version}"
    case Services.UI                  => s"galacticfog/gestalt-ui-react:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_DOTNET    => s"galacticfog/gestalt-laser-executor-dotnet:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_JS        => s"galacticfog/gestalt-laser-executor-js:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_JVM       => s"galacticfog/gestalt-laser-executor-jvm:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_PYTHON    => s"galacticfog/gestalt-laser-executor-python:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_GOLANG    => s"galacticfog/gestalt-laser-executor-golang:release-${BuildInfo.version}"
    case LaserExecutors.EXECUTOR_RUBY      => s"galacticfog/gestalt-laser-executor-ruby:release-${BuildInfo.version}"
  }

  case class DatabaseConfig( provision: Boolean,
                             provisionedSize: Int,
                             numSecondaries: Int,
                             pgreplToken: String,
                             hostname: String,
                             port: Int,
                             username: String,
                             password: String,
                             prefix: String )

  case object DatabaseConfig {
    val DEFAULT_NUM_SECONDARIES = 0
    val DEFAULT_KILL_GRACE_PERIOD = 300
  }

  case class Debug( cpu: Option[Double], mem: Option[Int] )

  case class MarathonConfig( marathonLbUrl: Option[String],
                             appGroup: String,
                             tld: Option[String],
                             baseUrl: String,
                             frameworkName: String,
                             clusterName: String,
                             jvmOverheadFactor: Double,
                             networkName: Option[String],
                             mesosHealthChecks: Option[Boolean] )

  case class SecurityConfig( username: String,
                             password: Option[String],
                             key: Option[String],
                             secret: Option[String] )

  case class LaserConfig( minCoolExecutors: Int,
                          scaleDownTimeout: Int,
                          minPortRange: Int,
                          maxPortRange: Int,
                          enabledRuntimes: Seq[LaserRuntime],
                          ethernetPort: Option[String],
                          advertiseHost: Option[String] )

  implicit val acsServiceAcctFmt = Json.format[DCOSACSServiceAccountCreds]

  case class DCOSACSServiceAccountCreds( login_endpoint : String,
                                         uid : String,
                                         private_key : String,
                                         scheme: String )

  case object LaserConfig {
    val DEFAULT_MIN_PORT_RANGE = 60000
    val DEFAULT_MAX_PORT_RANGE = 60500
    val DEFAULT_MIN_COOL_EXECS = 1
    val DEFAULT_SCALE_DOWN_TIMEOUT = 15

    case class LaserRuntime(name: String, runtime: String, image: String, cmd: String, metaType: String)

    val KNOWN_LASER_RUNTIMES: Map[WellKnownLaserExecutor, LaserRuntime] = Map(
      EXECUTOR_JS     -> LaserRuntime("js-executor",     "nodejs",        "", "bin/gestalt-laser-executor-js"    , "NodeJS"),
      EXECUTOR_JVM    -> LaserRuntime("jvm-executor",    "java;scala",    "", "bin/gestalt-laser-executor-jvm"   , "Java"),
      EXECUTOR_DOTNET -> LaserRuntime("dotnet-executor", "csharp;dotnet", "", "bin/gestalt-laser-executor-dotnet", "CSharp"),
      EXECUTOR_PYTHON -> LaserRuntime("python-executor", "python",        "", "bin/gestalt-laser-executor-python", "Python"),
      EXECUTOR_RUBY   -> LaserRuntime("ruby-executor",   "ruby",          "", "bin/gestalt-laser-executor-ruby"  , "Ruby"),
      EXECUTOR_GOLANG -> LaserRuntime("golang-executor", "golang",        "", "bin/gestalt-laser-executor-golang", "GoLang")
    )
  }

  case class MetaConfig( companyName: String )

  object MetaConfig {
    val DEFAULT_COMPANY_NAME = "A Galactic Fog Customer"

    val SETUP_LAMBDA_URL = "https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.2.0/js_lambda/demo-setup.js"
    val TDOWN_LAMBDA_URL = "https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.2.0/js_lambda/demo-teardown.js"
  }

}
