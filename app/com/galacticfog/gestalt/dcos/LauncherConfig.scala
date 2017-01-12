package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher
import com.google.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class LauncherConfig @Inject()(config: Configuration) {

  import LauncherConfig._
  import LauncherConfig.Services._

  def getString(path: String, default: String): String = config.getString(path).getOrElse(default)

  def getInt(path: String, default: Int): Int = config.getInt(path).getOrElse(default)

  def getBool(path: String, default: Boolean): Boolean = config.getBoolean(path).getOrElse(default)

  val database = DatabaseConfig(
    provision = getBool("database.provision", true),
    provisionedSize = getInt("database.provisioned-size", 100),
    hostname = getString("database.hostname", "data.gestalt.marathon.mesos"),
    port = getInt("database.port", 5432),
    username = getString("database.username", "gestaltdev"),
    password = getString("database.password", "letmein"),
    prefix = getString("database.prefix", "gestalt-")
  )

  val mesos = MesosConfig(
    master = getString("mesos.master", "master.mesos:5050"),
    schedulerHostname = getString("scheduler.hostname", java.net.InetAddress.getLocalHost.getHostName),
    schedulerName = getString("scheduler.name", "gestalt-framework-scheduler")
  )

  val marathon = MarathonConfig(
    appGroup = getString("marathon.app-group", DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/"),
    tld = config.getString("marathon.tld"),
    baseUrl = getString("marathon.url", "http://marathon.mesos:8080")
  )

  val security = SecurityConfig(
    username = getString("security.username", "gestalt-admin"),
    password = config.getString("security.password"),
    key = config.getString("security.key"),
    secret = config.getString("security.secret")
  )

  val gestaltFrameworkVersion: Option[String] = config.getString("gestalt-framework-version")

  protected[this] def vipBase(service: FrameworkService): String = {
    marathon.appGroup
      .stripPrefix("/")
      .stripSuffix("/")
      .split("/")
      .reverse
      .foldLeft(service.name)(_ + "." + _)
  }

  def vipLabel(service: ServiceEndpoint): String = "/" + vipBase(service) + ":" + service.port

  def vipHostname(service: FrameworkService): String = vipBase(service) + ".marathon.l4lb.thisdcos.directory"

  val allServices = {
    if (database.provision) GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService)
    else GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService).filterNot(_ == "data")
  }

  def dockerImage(service: FrameworkService) = {
    config
      .getString(s"containers.${service.name}")
      .orElse(gestaltFrameworkVersion.map(
        ensVer => service match {
          case RABBIT | RABBIT_HTTP | KONG_GATEWAY | KONG_SERVICE =>
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

  sealed trait Dockerable

  sealed trait FrameworkService extends Dockerable {
    def name: String
  }

  trait ServiceEndpoint extends FrameworkService {
    def port: Int
  }

  object Services {
    case object RABBIT       extends FrameworkService {val name = "rabbit"}
    case object RABBIT_AMQP  extends ServiceEndpoint  {val name = RABBIT.name;   val port = 5672}
    case object RABBIT_HTTP  extends ServiceEndpoint  {val name = RABBIT.name;   val port = 15672}
    case object KONG         extends FrameworkService {val name = "kong";}
    case object KONG_GATEWAY extends ServiceEndpoint  {val name = KONG.name;     val port = 8000}
    case object KONG_SERVICE extends ServiceEndpoint  {val name = KONG.name;     val port = 8001}
    case object DATA         extends ServiceEndpoint  {val name = "data";        val port = 5432}
    case object SECURITY     extends ServiceEndpoint  {val name = "security";    val port = 9455}
    case object META         extends ServiceEndpoint  {val name = "meta";        val port = 14374}
    case object LAMBDA       extends ServiceEndpoint  {val name = "lambda";      val port = 1111}
    case object POLICY       extends ServiceEndpoint  {val name = "policy";      val port = 9999}
    case object API_GATEWAY  extends ServiceEndpoint  {val name = "api-gateway"; val port = 6473}
    case object API_PROXY    extends ServiceEndpoint  {val name = "api-proxy";   val port = 81}
    case object UI           extends ServiceEndpoint  {val name = "ui";          val port = 80}

    val allServices: Seq[FrameworkService] = Seq( RABBIT, KONG, DATA, SECURITY, META, LAMBDA, POLICY, API_GATEWAY, API_PROXY, UI )

    def fromName(serviceName: String) = allServices.find(_.name == serviceName)
  }

  object Executors {
    case object EXECUTOR_DOTNET extends FrameworkService {val name = "laser-executor-dotnet"}
    case object EXECUTOR_JS     extends FrameworkService {val name = "laser-executor-js"}
    case object EXECUTOR_JVM    extends FrameworkService {val name = "laser-executor-jvm"}
    case object EXECUTOR_PYTHON extends FrameworkService {val name = "laser-executor-python"}
    case object EXECUTOR_GOLANG extends FrameworkService {val name = "laser-executor-golang"}
    case object EXECUTOR_RUBY   extends FrameworkService {val name = "laser-executor-ruby"}
  }

  val DEFAULT_DOCKER_IMAGES: Map[Dockerable,String] = Map(
    Services.RABBIT              -> "galacticfog/rabbit:dcos-latest",
    Services.RABBIT_HTTP         -> "galacticfog/rabbit:dcos-latest",
    Services.KONG_GATEWAY        -> "galacticfog/kong:dcos-latest",
    Services.KONG_SERVICE        -> "galacticfog/kong:dcos-latest",
    Services.DATA                -> "galacticfog/gestalt-data:dcos-latest",
    Services.SECURITY            -> "galacticfog/gestalt-security:dcos-latest",
    Services.META                -> "galacticfog/gestalt-meta:dcos-latest",
    Services.POLICY              -> "galacticfog/gestalt-policy:dcos-latest",
    Services.LAMBDA              -> "galacticfog/gestalt-lambda:dcos-latest",
    Services.API_GATEWAY         -> "galacticfog/gestalt-api-gateway:dcos-latest",
    Services.API_PROXY           -> "galacticfog/gestalt-api-proxy:dcos-latest",
    Services.UI                  -> "galacticfog/gestalt-ui:dcos-latest",
    Executors.EXECUTOR_DOTNET    -> "galacticfog/gestalt-laser-executor-dotnet:dcos-latest",
    Executors.EXECUTOR_JS        -> "galacticfog/gestalt-laser-executor-js:dcos-latest",
    Executors.EXECUTOR_JVM       -> "galacticfog/gestalt-laser-executor-jvm:dcos-latest",
    Executors.EXECUTOR_PYTHON    -> "galacticfog/gestalt-laser-executor-python:dcos-latest",
    Executors.EXECUTOR_GOLANG    -> "galacticfog/gestalt-laser-executor-golang:dcos-latest",
    Executors.EXECUTOR_RUBY      -> "galacticfog/gestalt-laser-executor-ruby:dcos-latest"
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

  case class MarathonConfig( appGroup: String,
                             tld: Option[String],
                             baseUrl: String )

  case class SecurityConfig( username: String,
                             password: Option[String],
                             key: Option[String],
                             secret: Option[String] )

}
