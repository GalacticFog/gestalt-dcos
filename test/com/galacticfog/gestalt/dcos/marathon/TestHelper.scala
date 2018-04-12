package com.galacticfog.gestalt.dcos.marathon

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.google.inject.AbstractModule
import mockws.{MockWS, Route}
import modules.WSClientFactory
import net.codingwell.scalaguice.ScalaModule
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

trait TestHelper {

  val authToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6InNlY3JldCIsInR5cCI6IkpXVCJ9.eyJ1aWQiOiJkZW1vX2dlc3RhbHRfbWV0YSJ9.RP5MhJPey2mDXOJlu1GFcQ_TlWZzYnr_6N7AwDbB0jqJC3bsLR8QxKZVbzk_JInwO5QN_-BVK5bvxP5zyo4KhVotsugH5eP_iTSDyyx7iKWOK4oPmFlJglaXGRE_KEuySAeZCNTnDIrfUWnB21WwS92MGr6B4rFZ-IzVSmygzO-LgxM-ZU9_b9kyKLOUXcQLgHFLY-qJMWou98dTv36lhjqx65iKQ5PT53KjGtL6OQ-1vqXse5ynCJGsXk3HBXV4P_w42RJBIAWiIbsUfgN85sGTVPvtHO-o-GJMknf7G0FiwfGtsYS3n05kirNIwsZS54RX03TNlxq0Vg48eWGZKQ"
  val testServiceId = "meta-dcos-provider"
  val testPrivateKey = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC9OzC0iseKnsqd\nu82KvTav6q+j4MoSS3mGGPZIA2JaD/cMjpzBtaaOxIbcyLWt2M8hwdO3TLXCZiW2\nybz2Koeo3+vNphnO7U4ZggSIuM+RYfhUUnQ79yiYKmL3z93HRrvZBlulG3yOFo5y\n30IFKqyt2QKlPy3ObCtZYwT4opYNnkev/pubtOjsjdkU9/u088eiLfVHwSwpBxjG\n2wbpFVGyN3p55UHW3K6QUrUw8B7EOF2A5EXzgR5GmAgL6SjuzEdghumqdMcSxGoE\n4pL3Y6LHer391ITdxO819o0i3cfglvgXxFGZSsiRVV89X15n8pEbP73cD3sRxnwe\nIwW860ZnAgMBAAECggEAIKUXb+4JIobmWXPOr8KYrpyEFHdxJNrUaifgROggjXz3\nl7j6nghiZXrN8UTG4ujmQuKXTaX0LUdF9lSzPpxzrtSCb4XaKfKSaKAffB614FTQ\nbGuVFcs7u5SEYk//6KLxQS1xnfgx8qk9hd+yGgYUqCEp7awKkPPkPpVwhBw4WrzJ\nkYxJ3bIT7j3svTr5uhno7cFso5jhfFyMA7PruHGNfyOWLIgzgw5qwRUK1WLMyk88\nJivrDRbvuskWK7pxvLrRQ/VA34LvGKLroj9Gqw9HIDGbY526PPjFo/uDq8ErHBsQ\nBdoagN6VihX5YjXdi3eF8mIcaFYBOQj6zB+Kfmkc0QKBgQDjkIemfgpHEMcRsinm\ni0WLlZGD8hjFTNku1Pki5sFffXcHR+FImrEUXL/NqJr8iqIeJ+1cx3OAiBm8PHh4\nl+LYz4H2TlvIEEURmOwLiBbh49N4o7T9the+PluDGLsZ9ka3AGHP1LBcvwYJdf7v\nubK3eky1QQSI5Ce6+uayU76QFQKBgQDU4G4j2eAIVTDQ0xMfJYXFaIh2eVqTkv83\nPeskWhAQcPUKzyX7bPHSdSbx+91ZW5iL8GX4DFp+JBiQFSqNq1tqhLwz9xHTxYrj\nGvi6MUJ4LCOihbU+6JIYuOdxq3govxtnJ+lE4cmwr5Y4HM1wx2dxba9EsItLrzkj\nHGPNDJ6fiwKBgCXgPHO9rsA9TqTnXon8zEp7TokDlpPgQpXE5OKmPbFDFLilgh2v\ngaG9/j6gvYsjF/Ck/KDgoZzXClGGTxbjUOJ9R0hTqnsWGijfpwoUUJqwbNY7iThh\nQnprrpeXWizsDMEQ0zbgU6pcMQkKFrCX2+Ml+/Z/J94Q+3vnntY3khQxAoGAdUkh\n5cbI1E57ktJ4mpSF23n4la3O5bf7vWf0AhdM+oIBwG7ZMmmX4qiBSJnIHs+EgLV2\nuO+1fAJPNjMzOtLKjymKt+bMf607FF1r5Mn3IVbQW17nuT1SISTe/5XFok2Iv5ER\nyM3N3fcgANJ9rkFvEOOpyWKrnItyI5IkunjVfHkCgYEAjmAjQOQt5eCO9kGitL7X\ntQGn8TWWHRCjMm1w3ith7bPp11WrdeyfNuUAB7weQjk2qjAIKTOGWtIRqc36OLPA\nkwF1GDyFXvLqJej/2ZLfytyjhetLAQnRL0qOgCi7EU5+YLXuYnn7zPEJgrR3ogX4\n4rvG4NIQ8wG0sEUTnr06nck=\n-----END PRIVATE KEY-----"
  val testPublicKey  = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvTswtIrHip7KnbvNir02\nr+qvo+DKEkt5hhj2SANiWg/3DI6cwbWmjsSG3Mi1rdjPIcHTt0y1wmYltsm89iqH\nqN/rzaYZzu1OGYIEiLjPkWH4VFJ0O/comCpi98/dx0a72QZbpRt8jhaOct9CBSqs\nrdkCpT8tzmwrWWME+KKWDZ5Hr/6bm7To7I3ZFPf7tPPHoi31R8EsKQcYxtsG6RVR\nsjd6eeVB1tyukFK1MPAexDhdgORF84EeRpgIC+ko7sxHYIbpqnTHEsRqBOKS92Oi\nx3q9/dSE3cTvNfaNIt3H4Jb4F8RRmUrIkVVfPV9eZ/KRGz+93A97EcZ8HiMFvOtG\nZwIDAQAB\n-----END PUBLIC KEY-----"
  val testDcosUrl = "https://m1.dcos/acs/api/v1/auth/login"
  val marathonBaseUrl = "https://my-dcos-cluster/service/marathon"

  val testAppId = "/test/test/test-app"

  val dummyAppPayload = MarathonAppPayload(
    id = Some(testAppId),
    instances = Some(1),
    cpus = Some(1),
    mem = Some(1),
    container = Some(MarathonContainerInfo())
  )


  object TestConfigs {
    val noAuthConfig = Seq(
      "auth.method" -> "none"
    )

    val authConfig = Seq(
      "auth.method" -> "acs",
      "auth.acs_service_acct_creds" -> Json.obj(
        "login_endpoint" -> testDcosUrl,
        "uid" -> testServiceId,
        "private_key" -> testPrivateKey,
        "scheme" -> "RS256"
      ).toString
    )

    val marathonConfig = Seq(
      "marathon.url" -> marathonBaseUrl
    )
  }

  case class TestModule(ws: WSClient) extends AbstractModule with AkkaGuiceSupport with ScalaModule {
    override def configure(): Unit = {
      bind[WSClientFactory].toInstance(new WSClientFactory {
        def getClient = ws
      })
    }
  }

  abstract class WithConfig( config: Seq[(String,Any)] = Seq.empty,
                             routes: MockWS.Routes = PartialFunction.empty ) extends TestKit(ActorSystem("test-system")) with Scope {

    val testRoute = Route(routes)

    val injector =
      new GuiceApplicationBuilder()
        .disable[modules.Module]
        .bindings(TestModule(MockWS(testRoute)))
        .configure(config:_*)
        .injector
  }

}
