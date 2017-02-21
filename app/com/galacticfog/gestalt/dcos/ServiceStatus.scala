package com.galacticfog.gestalt.dcos

sealed trait ServiceStatus

object ServiceStatus {
  final case object LAUNCHING extends ServiceStatus
  final case object DELETING  extends ServiceStatus
  final case object WAITING   extends ServiceStatus
  final case object STAGING   extends ServiceStatus
  final case object STOPPED   extends ServiceStatus
  final case object UNHEALTHY extends ServiceStatus
  final case object HEALTHY   extends ServiceStatus
  final case object RUNNING   extends ServiceStatus
  final case object NOT_FOUND extends ServiceStatus
}
