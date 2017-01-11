package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.marathon.GestaltMarathonLauncher
import com.google.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class LauncherConfig @Inject()(config: Configuration) {

  import LauncherConfig._

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

  def vip(svcName: String): String = {
    marathon.appGroup
      .stripPrefix("/")
      .stripSuffix("/")
      .split("/")
      .reverse
      .foldLeft("/" + svcName)(_ + "." + _)
  }

  val allServices = {
    if (database.provision) GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService)
    else GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService).filterNot(_ == "data")
  }

  def dockerImage(service: String) = {
    config.getString(s"containers.${service}")
      .orElse(gestaltFrameworkVersion.map(ensVer => s"galacticfog/${service}:dcos-${ensVer}"))
      .getOrElse(DEFAULT_DOCKER_IMAGES(service))
  }

}

object LauncherConfig {

  val DEFAULT_APP_GROUP = "gestalt-framework-tasks"

  val DEFAULT_DOCKER_IMAGES = Map(
    "rabbit"                     -> "galacticfog/rabbit:dcos-latest",
    "kong"                       -> "galacticfog/kong:dcos-latest",
    "gestalt-data"               -> "galacticfog/gestalt-data:dcos-latest",
    "gestalt-security"           -> "galacticfog/gestalt-security:dcos-latest",
    "gestalt-meta"               -> "galacticfog/gestalt-meta:dcos-latest",
    "gestalt-policy"             -> "galacticfog/gestalt-policy:dcos-latest",
    "gestalt-lambda"             -> "galacticfog/gestalt-lambda:dcos-latest",
    "gestalt-api-gateway"        -> "galacticfog/gestalt-api-gateway:dcos-latest",
    "gestalt-api-proxy"          -> "galacticfog/gestalt-api-proxy:dcos-latest",
    "gestalt-ui"                 -> "galacticfog/gestalt-ui:dcos-latest",
    "lambda-javascript-executor" -> "galacticfog/lambda-javascript-executor:dcos-latest",
    "lambda-java-executor"       -> "galacticfog/lambda-java-executor:dcos-latest",
    "lambda-dotnet-executor"     -> "galacticfog/lambda-dotnet-executor:dcos-latest"
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
