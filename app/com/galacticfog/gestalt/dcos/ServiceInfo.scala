package com.galacticfog.gestalt.dcos

import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import play.api.libs.json.{JsString, JsValue, Json, Writes}

case class ServiceInfo(service: FrameworkService, vhosts: Seq[String], hostname: Option[String], ports: Seq[String], status: ServiceStatus)

case object ServiceInfo {
  implicit val statusFmt = new Writes[ServiceStatus] {
    override def writes(o: ServiceStatus): JsValue = JsString(o.toString)
  }
  implicit val serviceWrites = new Writes[FrameworkService] {
    override def writes(o: FrameworkService): JsValue = Json.obj(
      "serviceName" -> o.name
    )
  }
  implicit val serviceInfoWrites = new Writes[ServiceInfo] {
    override def writes(si: ServiceInfo): JsValue = Json.obj(
      "serviceName" -> si.service.name,
      "vhosts" -> Json.toJson(si.vhosts),
      "hostname" -> Json.toJson(si.hostname),
      "ports" -> Json.toJson(si.ports),
      "status" -> Json.toJson(si.status)
    )
  }
}

