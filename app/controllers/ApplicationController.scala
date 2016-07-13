package controllers

import javax.inject._
import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.marathon.{ShutdownRequest, MarathonSSEClient}
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import views.html.index

import scala.concurrent.ExecutionContext

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

  def dashboard = Action {
    Ok(index.render(webJarAssets))
  }

  def data() = Action.async {
    marClient.getAllServices() map { r =>
      Ok(Json.toJson(r))
    }
  }

  def shutdown(shutdownDB: Boolean) = Action {
    Logger.info(s"received shutdown request: shutdownDB == ${shutdownDB}")
    schedulerFSM ! ShutdownRequest(shutdownDB = shutdownDB)
    Accepted(Json.obj("message" -> "Framework shutting down"))
  }

}
