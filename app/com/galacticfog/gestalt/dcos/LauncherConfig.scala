package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.marathon.{GestaltMarathonLauncher, LaunchingState}
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import scala.concurrent.duration._
import scala.language.postfixOps

@Singleton
class LauncherConfig @Inject()(config: Configuration) {

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
    jvmOverheadFactor = getDouble("marathon.jvm-overhead-factor", 2.0)
  )

  val database = DatabaseConfig(
    provision = getBool("database.provision", true),
    provisionedSize = getInt("database.provisioned-size", 100),
    hostname = getString("database.hostname", marathon.appGroup.replaceAll("/","-") + "-data"),
    port = getInt("database.port", 5432),
    username = getString("database.username", "gestaltdev"),
    password = getString("database.password", "letmein"),
    prefix = getString("database.prefix", "gestalt-")
  )

  val laser = LaserConfig(
    minCoolExecutors = getInt("laser.min-cool-executors", LaserConfig.DEFAULT_MIN_COOL_EXECS),
    scaleDownTimeout = getInt("laser.scale-down-timeout", LaserConfig.DEFAULT_SCALE_DOWN_TIMEOUT),
    minPortRange     = getInt("laser.min-port-range", LaserConfig.DEFAULT_MIN_PORT_RANGE),
    maxPortRange     = getInt("laser.max-port-range", LaserConfig.DEFAULT_MAX_PORT_RANGE)
  )

  val security = SecurityConfig(
    username = getString("security.username", "gestalt-admin"),
    password = config.getString("security.password"),
    key = config.getString("security.key"),
    secret = config.getString("security.secret")
  )

  val gestaltFrameworkVersion: Option[String] = config.getString("gestalt-framework-version")

  protected[this] def vipBase(service: Dockerable): String = {
    marathon.appGroup
      .split("/")
      .foldRight(service.name)(_ + "-" + _)
  }

  def vipLabel(service: ServiceEndpoint): String = "/" + vipBase(service) + ":" + service.port

  def vipHostname(service: Dockerable): String = vipBase(service) + ".marathon.l4lb.thisdcos.directory"

  val provisionedServices = {
    val all = GestaltMarathonLauncher.LAUNCH_ORDER.collect({case s: LaunchingState => s.targetService})
    if (database.provision) all
    else all.filterNot(_ == DATA)
  }

  def dockerImage(service: Dockerable) = {
    config
      .getString(s"containers.${service.name}")
      .orElse(gestaltFrameworkVersion.map(
        ensVer => service match {
          case RABBIT | KONG =>
            s"galacticfog/${service.name}:dcos-${ensVer}"
          case _ =>
            s"galacticfog/gestalt-${service.name}:dcos-${ensVer}"
        }
      ))
      .getOrElse(DEFAULT_DOCKER_IMAGES(service))
  }

}

object LauncherConfig {

  val DEFAULT_APP_GROUP = "gestalt-framework-tasks"

  val MARATHON_RECONNECT_DELAY = 10 seconds

  val EXTERNAL_API_CALL_TIMEOUT = 30 seconds

  val EXTERNAL_API_RETRY_INTERVAL = 5 seconds

  sealed trait Dockerable {
    def name: String
  }

  sealed trait FrameworkService extends Dockerable {
    def name: String
    def cpu: Double
    def mem: Int
  }

  trait ServiceEndpoint extends Dockerable {
    def name: String
    def port: Int
  }

  object Services {
    case object RABBIT       extends FrameworkService                       {val name = "rabbit";      val cpu = 0.50; val mem = 256;}
    case object KONG         extends FrameworkService                       {val name = "kong";        val cpu = 0.50; val mem = 128;}
    case object DATA         extends FrameworkService with ServiceEndpoint  {val name = "data";        val cpu = 1.00; val mem = 512;  val port = 5432}
    case object SECURITY     extends FrameworkService with ServiceEndpoint  {val name = "security";    val cpu = 0.50; val mem = 1536; val port = 9455}
    case object META         extends FrameworkService with ServiceEndpoint  {val name = "meta";        val cpu = 1.50; val mem = 1536; val port = 14374}
    case object LASER        extends FrameworkService with ServiceEndpoint  {val name = "laser";       val cpu = 0.50; val mem = 1536; val port = 1111}
    case object POLICY       extends FrameworkService with ServiceEndpoint  {val name = "policy";      val cpu = 0.25; val mem = 1024; val port = 9999}
    case object API_GATEWAY  extends FrameworkService with ServiceEndpoint  {val name = "api-gateway"; val cpu = 0.25; val mem = 1024; val port = 6473}
    case object API_PROXY    extends FrameworkService with ServiceEndpoint  {val name = "api-proxy";   val cpu = 0.50; val mem = 128;  val port = 81}
    case object UI           extends FrameworkService with ServiceEndpoint  {val name = "ui";          val cpu = 0.25; val mem = 128;  val port = 80}

    case object RABBIT_AMQP  extends ServiceEndpoint                        {val name = RABBIT.name;                                   val port = 5672}
    case object RABBIT_HTTP  extends ServiceEndpoint                        {val name = RABBIT.name;                                   val port = 15672}
    case object KONG_GATEWAY extends ServiceEndpoint                        {val name = KONG.name;                                     val port = 8000}
    case object KONG_SERVICE extends ServiceEndpoint                        {val name = KONG.name;                                     val port = 8001}

    val allServices: Seq[FrameworkService] = Seq( RABBIT, KONG, DATA, SECURITY, META, LASER, POLICY, API_GATEWAY, API_PROXY, UI )

    def fromName(serviceName: String) = allServices.find(_.name == serviceName)
  }

  object Executors {
    case object EXECUTOR_DOTNET extends Dockerable {val name = "laser-executor-dotnet"}
    case object EXECUTOR_JS     extends Dockerable {val name = "laser-executor-js"}
    case object EXECUTOR_JVM    extends Dockerable {val name = "laser-executor-jvm"}
    case object EXECUTOR_PYTHON extends Dockerable {val name = "laser-executor-python"}
    case object EXECUTOR_GOLANG extends Dockerable {val name = "laser-executor-golang"}
    case object EXECUTOR_RUBY   extends Dockerable {val name = "laser-executor-ruby"}
  }

  val DEFAULT_DOCKER_IMAGES: Map[Dockerable,String] = Map(
    Services.RABBIT              -> s"galacticfog/rabbit:dcos-${BuildInfo.version}",
    Services.KONG                -> s"galacticfog/kong:dcos-${BuildInfo.version}",
    Services.DATA                -> s"galacticfog/gestalt-data:dcos-${BuildInfo.version}",
    Services.SECURITY            -> s"galacticfog/gestalt-security:dcos-${BuildInfo.version}",
    Services.META                -> s"galacticfog/gestalt-meta:dcos-${BuildInfo.version}",
    Services.POLICY              -> s"galacticfog/gestalt-policy:dcos-${BuildInfo.version}",
    Services.LASER               -> s"galacticfog/gestalt-laser:dcos-${BuildInfo.version}",
    Services.API_GATEWAY         -> s"galacticfog/gestalt-api-gateway:dcos-${BuildInfo.version}",
    Services.API_PROXY           -> s"galacticfog/gestalt-api-proxy:dcos-${BuildInfo.version}",
    Services.UI                  -> s"galacticfog/gestalt-ui:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_DOTNET    -> s"galacticfog/gestalt-laser-executor-dotnet:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_JS        -> s"galacticfog/gestalt-laser-executor-js:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_JVM       -> s"galacticfog/gestalt-laser-executor-jvm:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_PYTHON    -> s"galacticfog/gestalt-laser-executor-python:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_GOLANG    -> s"galacticfog/gestalt-laser-executor-golang:dcos-${BuildInfo.version}",
    Executors.EXECUTOR_RUBY      -> s"galacticfog/gestalt-laser-executor-ruby:dcos-${BuildInfo.version}"
  )

  case class MesosConfig( master: String,
                          schedulerHostname: String,
                          schedulerName: String )

  case class DatabaseConfig( provision: Boolean,
                             provisionedSize: Int,
                             hostname: String,
                             port: Int,
                             username: String,
                             password: String,
                             prefix: String )

  case class MarathonConfig( marathonLbUrl: Option[String],
                             appGroup: String,
                             tld: Option[String],
                             baseUrl: String,
                             jvmOverheadFactor: Double )

  case class SecurityConfig( username: String,
                             password: Option[String],
                             key: Option[String],
                             secret: Option[String] )

  case class LaserConfig( minCoolExecutors: Int,
                          scaleDownTimeout: Int,
                          minPortRange: Int,
                          maxPortRange: Int )

  case object LaserConfig {
    val DEFAULT_MIN_PORT_RANGE = 60000
    val DEFAULT_MAX_PORT_RANGE = 60500
    val DEFAULT_MIN_COOL_EXECS = 1
    val DEFAULT_SCALE_DOWN_TIMEOUT = 15
  }

}
