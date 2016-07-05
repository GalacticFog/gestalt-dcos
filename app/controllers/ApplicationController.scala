package controllers

import javax.inject._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.galacticfog.gestalt.dcos.actors.{ServiceStatusResponse, ServiceStatusRequest}
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import views.html.index
import scala.concurrent.duration._

import scala.concurrent.{Future, ExecutionContext}

@Singleton
class ApplicationController @Inject() (webJarAssets: WebJarAssets)(implicit ec: ExecutionContext) extends Controller {

  def health = Action {
    Ok(Json.obj(
      "status" -> "healthy"
    ))
  }

  def dashboard = Action.async {
//    implicit val timeout: Timeout = 3.seconds
//    val fStates = schedulerFSM ? ServiceStatusRequest
//    fStates map {
//      case ServiceStatusResponse(states) => Ok(index.render(webJarAssets, states))
//      case _ => InternalServerError("could not query states")
      Future(Ok(""))
  }

}
