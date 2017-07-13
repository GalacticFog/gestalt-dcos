package modules

import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import akka.stream.Materializer
import com.galacticfog.gestalt.dcos.{GestaltTaskFactory, LauncherConfig}
import com.galacticfog.gestalt.dcos.launcher.LauncherFSM
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor
import com.google.inject.{AbstractModule, Singleton}
import io.netty.handler.ssl.SslContextBuilder
import net.codingwell.scalaguice.ScalaModule
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.netty.ssl.InsecureTrustManagerFactory
import play.api.Logger
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.libs.ws.ahc.AhcWSClient

class Module extends AbstractModule with AkkaGuiceSupport with ScalaModule {

  override def configure(): Unit = {
    bind[GestaltTaskFactory].asEagerSingleton()
    bindActor[LauncherFSM]("scheduler-actor")
    bind[Kickstart].asEagerSingleton()
    bind[WSClientFactory].to[DefaultWSClientFactory]
    bindActor[DCOSAuthTokenActor](DCOSAuthTokenActor.name)
  }

}

trait WSClientFactory {
  def getClient: WSClient
}

@Singleton
class DefaultWSClientFactory @Inject()(config: LauncherConfig, defaultClient: WSClient )
                                      ( implicit val mat: Materializer ) extends WSClientFactory {

  private lazy val permissiveClient = {
    val config = new DefaultAsyncHttpClientConfig.Builder()
      .setFollowRedirect(true)
      .setSslContext(SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE).build)
      .build()
    new AhcWSClient(config)(mat)
  }

  def getClient: WSClient = {
    if (config.acceptAnyCertificate.contains(true)) {
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
