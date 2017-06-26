package com.galacticfog.gestalt.dcos.marathon

import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, PrivateKey, PublicKey}
import java.util.UUID

import akka.actor.{Actor, Props}
import akka.pattern.ask
import com.galacticfog.gestalt.dcos.marathon.DCOSAuthTokenActor.{DCOSAuthTokenError, DCOSAuthTokenResponse}
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import io.jsonwebtoken.{Jwts, SignatureAlgorithm}
import modules.WSClientFactory
import org.apache.commons.codec.binary.Base64
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.ws.WSClient

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class DCOSAuthTokenActor @Inject() ( clientFac: WSClientFactory ) extends Actor {

  val log = Logger(this.getClass)

  // TODO: once we start using cached tokens, how do we handle that the underlying credentials may change? need to store enough so that we can check/invalidate

  // TODO: if we automatically refresh tokens, we'll need to keep the url and the secret

  val requestChild = context.actorOf(Props(classOf[DCOSAuthTokenRequestActor], clientFac.getClient))

  var acsAuthorizationToken: Option[String] = _

  // use the error kernel pattern to help protect our state from being wiped...
  // if we still manage to fail, it's not the end of the world, because the tokens will be regenerated
  override def receive: Receive = {
    case r: DCOSAuthTokenActor.DCOSAuthTokenRequest =>
      import context.dispatcher
      val s = sender()
      val f = requestChild.ask(r)(30 seconds)
      f.onComplete {
        case Success(response) =>
          if (response.isInstanceOf[DCOSAuthTokenResponse]) {
            acsAuthorizationToken = Some(response.asInstanceOf[DCOSAuthTokenResponse].authToken)
          }
          s ! response
        case Failure(t) =>
          s ! DCOSAuthTokenError(t.getMessage)
      }
    case e =>
      log.error(s"DCOSAuthTokenActor: unhandled message type: $e")
  }

}

object DCOSAuthTokenActor {

  final val name = "dcos-auth-token-actor"

  case class DCOSAuthTokenRequest( dcosUrl : String,
                                   serviceAccountId : String,
                                   privateKey : String )

  case class DCOSAuthTokenResponse( authToken: String )

  case class DCOSAuthTokenError( message: String )

  def strToPrivateKey(pkcs8: String): PrivateKey = {
    val encoded = Base64.decodeBase64(
      pkcs8.replace("-----BEGIN PRIVATE KEY-----\n", "")
           .replace("-----END PRIVATE KEY-----", "")
    )
    val kf = KeyFactory.getInstance("RSA");
    val keySpec = new PKCS8EncodedKeySpec(encoded);
    kf.generatePrivate(keySpec)
  }

  def strToPublicKey(pkcs8: String): PublicKey = {
    val encoded = Base64.decodeBase64(
      pkcs8.replace("-----BEGIN PUBLIC KEY-----\n", "")
        .replace("-----END PUBLIC KEY-----", "")
    )
    val kf = KeyFactory.getInstance("RSA");
    val keySpec = new X509EncodedKeySpec(encoded)
    kf.generatePublic(keySpec)
  }

}

class DCOSAuthTokenRequestActor(client: WSClient) extends Actor {
  val log = Logger(classOf[DCOSAuthTokenActor])

  override def receive: Receive = {
    case r: DCOSAuthTokenActor.DCOSAuthTokenRequest =>
      import context.dispatcher

      val s = sender()
      val claims: Map[String, AnyRef] = Map(
        "uid" -> r.serviceAccountId,
        "exp" -> float2Float(System.currentTimeMillis()/1000 + 5*60)
      )
      val f = for {
        jwt <- Future.fromTry(Try{
          Jwts.builder()
            .setClaims(claims.asJava)
            .signWith(SignatureAlgorithm.RS256, DCOSAuthTokenActor.strToPrivateKey(r.privateKey))
            .compact()
        })
        url = r.dcosUrl.stripPrefix("/") + "/acs/api/v1/auth/login"
        payload = Json.obj(
          "uid" -> r.serviceAccountId,
          "token" -> jwt
        )
        _ = log.debug("sending " + Json.prettyPrint(payload) + " to " + url)
        resp <- client.url(url).post(payload)
      } yield resp
      f.onComplete(_ match {
        case Success(resp) if resp.status == 200 =>
          log.trace("response from acs service: " + Json.stringify(resp.json))
          (resp.json \ "token").asOpt[String] match {
            case Some(tok) => s ! DCOSAuthTokenActor.DCOSAuthTokenResponse(tok)
            case None      => s ! DCOSAuthTokenActor.DCOSAuthTokenError("acs responded with 200 but response did not have parsable token")
          }
        case Success(resp) if resp.status != 200 =>
          log.trace("non-200 response form acs service: " + resp)
          s ! DCOSAuthTokenActor.DCOSAuthTokenError(resp.body)
        case Failure(t) =>
          s ! DCOSAuthTokenActor.DCOSAuthTokenError(t.getMessage)
      })
  }
}
