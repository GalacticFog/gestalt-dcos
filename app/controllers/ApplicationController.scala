package controllers

import javax.inject._
import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.marathon._
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import views.html.index
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext

@Singleton
class ApplicationController @Inject()(webJarAssets: WebJarAssets,
                                      @Named("scheduler-actor") schedulerFSM: ActorRef,
                                      gtf: GestaltTaskFactory,
                                      marClient: MarathonSSEClient)
                                     (implicit ec: ExecutionContext) extends Controller {

  def health = Action {
    Ok(Json.obj(
      "status" -> "healthy"
    ))
  }

  def dashboard = Action {
    Ok(index.render(webJarAssets,gtf.provisionDB, gtf.gestaltFrameworkEnsembleVersion getOrElse "latest"))
  }

  def data() = Action.async {
    implicit val timeout: Timeout = 15.seconds
    for {
      f <- schedulerFSM ? StatusRequest
      resp = f.asInstanceOf[StatusResponse]
    } yield Ok(Json.toJson(resp))
  }

  def shutdown(shutdownDB: Boolean) = Action {
    Logger.info(s"received shutdown request: shutdownDB == ${shutdownDB}")
    schedulerFSM ! ShutdownRequest(shutdownDB = shutdownDB)
    Accepted(Json.obj("message" -> "Framework shutting down"))
  }

  def restart() = Action {
    Logger.info(s"received restart request")
    schedulerFSM ! LaunchServicesRequest
    Accepted(Json.obj("message" -> "Starting framework services"))
  }

}
