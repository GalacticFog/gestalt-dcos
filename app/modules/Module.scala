package modules

import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import akka.stream.Materializer
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.launcher.LauncherFSM
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
    bind[WSClient].annotatedWithName("permissive-wsclient").to[PermissiveWSClient]
  }

}

@Singleton
class PermissiveWSClient @Inject() (implicit val mat: Materializer) extends WSClient {
  private val permissiveClient = {
    val config = new DefaultAsyncHttpClientConfig.Builder()
      .setFollowRedirect(true)
      .setSslContext(SslContextBuilder.forClient.trustManager(InsecureTrustManagerFactory.INSTANCE).build)
      .build()
    new AhcWSClient(config)(mat)
  }
  override def underlying[T]: T = permissiveClient.asInstanceOf[T]
  override def url(url: String): WSRequest = permissiveClient.url(url)
  override def close(): Unit = permissiveClient.close()
}

class Kickstart @Inject()(@Named("scheduler-actor") schedulerActor: ActorRef) {
  Logger.info("messaging the scheduler-actor to kickstart the launch")
  schedulerActor ! LauncherFSM.Messages.LaunchServicesRequest
}
