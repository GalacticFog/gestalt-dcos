package com.galacticfog.gestalt.dcos.launcher

import com.galacticfog.gestalt.dcos.LauncherConfig.FrameworkService
import com.galacticfog.gestalt.dcos.LauncherConfig.Services._

sealed trait LauncherState

sealed trait LaunchingState extends LauncherState {
  def targetService: FrameworkService
}

object States {

  // ordered/ordinary states...
  case object Uninitialized extends LauncherState

  case class LaunchingDB(index: Int) extends LaunchingState {
    val targetService = DATA(index)
  }

  case object LaunchingRabbit extends LaunchingState {
    val targetService = RABBIT
  }

  case object LaunchingSecurity extends LaunchingState {
    val targetService = SECURITY
  }

  case object RetrievingAPIKeys extends LauncherState

  case object LaunchingMeta extends LaunchingState {
    val targetService = META
  }

  case object BootstrappingMeta extends LauncherState

  case object SyncingMeta extends LauncherState

  case object ProvisioningMeta extends LauncherState

  case object LaunchingUI extends LaunchingState {
    val targetService = UI
  }

  case object AllServicesLaunched extends LauncherState

  // exceptional states
  case object ShuttingDown extends LauncherState

  case object Error extends LauncherState

}

