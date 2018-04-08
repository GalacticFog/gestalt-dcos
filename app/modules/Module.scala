package modules

import javax.inject.{Inject, Named}
import akka.actor.ActorRef
import akka.stream.Materializer
import com.galacticfog.gestalt.dcos.{GestaltTaskFactory, LauncherConfig}
import com.galacticfog.gestalt.dcos.launcher.LauncherFSM
import com.galacticfog.gestalt.dcos.marathon.{DCOSAuthTokenActor, MarathonSSEClient}
import com.google.inject.{AbstractModule, Singleton}
import com.typesafe.sslconfig.ssl.{SSLConfigSettings, SSLLooseConfig, SSLParametersConfig, TrustManagerConfig}
import net.codingwell.scalaguice.ScalaModule
import play.api.Logger
import play.api.libs.ws.{WSClient, WSClientConfig}
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}
import play.api.libs.concurrent.AkkaGuiceSupport

class Module extends AbstractModule with AkkaGuiceSupport with ScalaModule {

  override def configure(): Unit = {
    bind[GestaltTaskFactory].asEagerSingleton()
    bindActor[LauncherFSM]("scheduler-actor")
    bind[Kickstart].asEagerSingleton()
    bind[WSClientFactory].to[DefaultWSClientFactory]
    bindActor[DCOSAuthTokenActor](DCOSAuthTokenActor.name)
    bindActorFactory[MarathonSSEClient, MarathonSSEClient.Factory]
  }

}

trait WSClientFactory {
  def getClient: WSClient
}

@Singleton
class DefaultWSClientFactory @Inject()( config: LauncherConfig, defaultClient: WSClient )
                                      ( implicit val mat: Materializer ) extends WSClientFactory {

  private lazy val permissiveClient = {
    AhcWSClient(AhcWSClientConfig(WSClientConfig(
      ssl = SSLConfigSettings().withLoose(SSLLooseConfig().withAcceptAnyCertificate(true))
    )))
  }

  def getClient: WSClient = {
    if (config.acceptAnyCertificate) {
      permissiveClient
    } else {
      defaultClient
    }
  }
}

class Kickstart @Inject()(@Named("scheduler-actor") schedulerActor: ActorRef) {
  Logger.info("messaging the scheduler-actor to kickstart the launch")
  schedulerActor ! LauncherFSM.Messages.LaunchServicesRequest
}
