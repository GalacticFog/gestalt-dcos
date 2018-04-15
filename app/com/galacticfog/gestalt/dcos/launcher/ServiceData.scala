package com.galacticfog.gestalt.dcos.launcher

import akka.event.LoggingAdapter
import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.{GlobalConfig, ServiceInfo}
import com.galacticfog.gestalt.security.api.GestaltAPIKey

final case class ServiceData(statuses: Map[FrameworkService, ServiceInfo],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String],
                             errorStage: Option[String],
                             globalConfig: GlobalConfig,
                             connected: Boolean) {
  def getUrl(service: FrameworkService, log: LoggingAdapter): Option[String] = {
    statuses.get(service) match {
      case Some(ServiceInfo(_, _, Some(hostname), firstPort::_, _)) =>
        val url = hostname + ":" + firstPort.toString
        log.info(s"ServiceData#getUrl(${service.name}): constructed URL: $url")
        Some(url)
      case None =>
        log.warning(s"ServiceData#getUrl(${service.name}): service not found")
        None
      case Some(otherServiceInfo) =>
        log.warning(s"ServiceData#getUrl(${service.name}): service did not have sufficient data for constructing URL: ${otherServiceInfo}")
        None
    }
  }

  def update(update: ServiceInfo): ServiceData = this.update(Seq(update))

  def update(updates: Seq[ServiceInfo]): ServiceData = {
    copy(
      statuses = updates.foldLeft(statuses) {
        case (c, u) => c + (u.service -> u)
      }
    )
  }
}

case object ServiceData {
  def init: ServiceData = ServiceData(Map.empty, None, None, None, GlobalConfig.empty, connected = false)
}

