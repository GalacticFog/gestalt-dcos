import com.google.inject.AbstractModule
import modules.DefaultWSClientFactory
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AsyncHttpClientProvider
import play.api.test._
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient

class WSSpecs extends PlaySpecification with Mockito {

  case class TestModule(ws: WSClient) extends AbstractModule with ScalaModule {
    override def configure(): Unit = {
      bind[WSClient].toInstance(ws)
      bind[AsyncHttpClient].toProvider[AsyncHttpClientProvider]
    }
  }

  abstract class WithConfig( config: Seq[(String,Any)] = Seq.empty ) extends Scope {

    val mockWS = mock[WSClient]

    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .disable[play.api.libs.ws.ahc.AhcWSModule]
        .bindings(TestModule(mockWS))
        .configure(config:_*)
        .injector

    val wsFac = injector.instanceOf[DefaultWSClientFactory]
  }

  "DefaultWSClientFactory" should {

    "return permissive ssl for WS and akka-http if acceptAnyCertificate = true" in new WithConfig(Seq("acceptAnyCertificate" -> true)) {
      wsFac.getClient must_!= injector.instanceOf[WSClient]
    }

    "not return permissive ssl for WS and akka-http if acceptAnyCertificate is absent (backwards-compatible and secure-by-default)" in new WithConfig() {
      wsFac.getClient must_== injector.instanceOf[WSClient]
    }

    "not return permissive ssl for WS and akka-http if acceptAnyCertificate is false" in new WithConfig(Seq("acceptAnyCertificate" -> false)) {
      wsFac.getClient must_== injector.instanceOf[WSClient]
    }

  }

}

