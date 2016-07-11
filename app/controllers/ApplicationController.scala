package controllers

import javax.inject._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.galacticfog.gestalt.dcos.marathon.{ShutdownRequest, MarathonSSEClient}
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import views.html.{table, index}
import scala.concurrent.duration._

import scala.concurrent.{Future, ExecutionContext}

@Singleton
class ApplicationController @Inject()(webJarAssets: WebJarAssets,
                                       @Named("scheduler-actor") schedulerFSM: ActorRef,
                                       marClient: MarathonSSEClient)
                                     (implicit ec: ExecutionContext) extends Controller {

  def health = Action {
    Ok(Json.obj(
      "status" -> "healthy"
    ))
  }

  def dashboard = Action.async {
    val fStates = marClient.getAllServices()
    fStates map {
      case (globalStatus,_) => Ok(index.render(webJarAssets, globalStatus.launcherStage, globalStatus.error))
      case _ => InternalServerError("could not query states")
    }
  }

  def data() = Action.async {
    val fStates = marClient.getAllServices()
    fStates map {
      case (_,states) => Ok(table.render(webJarAssets, states))
      case _ => InternalServerError("could not query states")
    }
  }

  def shutdown() = Action {
    Logger.info("received shutdown request")
    schedulerFSM ! ShutdownRequest
    Accepted(Json.obj("message" -> "shutting down"))
  }

}
