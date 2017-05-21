package com.galacticfog.gestalt.dcos.launcher

import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.{GlobalConfig, ServiceInfo}
import com.galacticfog.gestalt.security.api.GestaltAPIKey

final case class ServiceData(statuses: Map[FrameworkService, ServiceInfo],
                             adminKey: Option[GestaltAPIKey],
                             error: Option[String],
                             errorStage: Option[String],
                             globalConfig: GlobalConfig,
                             connected: Boolean) {
  def getUrl(service: FrameworkService): Option[String] = {
    statuses.get(service).collect({
      case ServiceInfo(_, _, Some(hostname), firstPort::_, _) => hostname + ":" + firstPort.toString
    })
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
  def init: ServiceData = ServiceData(Map.empty, None, None, None, GlobalConfig.empty, false)
}

