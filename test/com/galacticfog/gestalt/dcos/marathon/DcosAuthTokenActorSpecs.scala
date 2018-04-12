package com.galacticfog.gestalt.dcos.marathon

import akka.pattern.ask
import akka.testkit.TestActorRef
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenRequest, DCOSAuthTokenResponse}
import mockws.{MockWS, MockWSHelpers, Route}
import modules.WSClientFactory
import org.specs2.mock.Mockito
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.Results._
import play.api.test._

class DcosAuthTokenActorSpecs extends PlaySpecification with Mockito with MockWSHelpers with TestHelper {

  object DCOSMocks {
    def mockAuth() = Route {
      case (POST,url) if url == testDcosUrl => Action {request =>
        val uid = (request.body.asJson.get \ "uid").as[String]
        val jws = io.jsonwebtoken.Jwts.parser().setSigningKey(DCOSAuthTokenActor.strToPublicKey(testPublicKey)).parseClaimsJws((request.body.asJson.get \ "token").as[String])
        if ( uid == testServiceId && jws.getBody.get("uid",classOf[String]) == testServiceId )
          Ok(Json.obj("token" -> authToken))
        else
          Unauthorized(Json.obj("message" -> "epic failure"))
      }
    }
  }

  "DCOSAuthTokenActor" should {

    "get token from DCOS ACS and persist it" in new WithConfig() {
      val acsLogin = DCOSMocks.mockAuth()
      val wscf = new WSClientFactory {
        override def getClient: WSClient = MockWS(acsLogin)
      }
      val tokenActor = TestActorRef(new DCOSAuthTokenActor(wscf))

      acsLogin.timeCalled must_== 0
      val token = await(tokenActor ? DCOSAuthTokenRequest(
        serviceAccountId = testServiceId,
        privateKey = testPrivateKey,
        dcosUrl = testDcosUrl
      ))
      token must_== DCOSAuthTokenResponse(authToken)
      tokenActor.underlyingActor.acsAuthorizationToken must beSome(authToken)
      acsLogin.timeCalled must_== 1
    }

  }

}
