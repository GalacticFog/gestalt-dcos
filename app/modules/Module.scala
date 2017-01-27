package modules

import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.marathon.{GestaltMarathonLauncher, MarathonSSEClient}
import com.google.inject.AbstractModule
import play.api.Logger
import play.api.libs.concurrent.AkkaGuiceSupport

class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure() = {
    bind(classOf[GestaltTaskFactory]).asEagerSingleton()
    bindActor[GestaltMarathonLauncher]("scheduler-actor")
    bind(classOf[Kickstart]).asEagerSingleton()
  }

}

class Kickstart @Inject()(@Named("scheduler-actor") schedulerActor: ActorRef) {
  Logger.info("messaging scheduler-actor to kickstart the launch")
  schedulerActor ! GestaltMarathonLauncher.LaunchServicesRequest
}
