package controllers

import javax.inject._

import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.{BuildInfo, GestaltTaskFactory, LauncherConfig}
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
                                      launcherConfig: LauncherConfig,
                                      gtf: GestaltTaskFactory)
                                     (implicit ec: ExecutionContext) extends Controller {

  def health = Action {
    Ok(Json.obj(
      "status" -> "healthy"
    ))
  }

  def dashboard = Action {
    Ok(index.render(webJarAssets, launcherConfig.database.provision, gtf.gestaltFrameworkEnsembleVersion getOrElse BuildInfo.version))
  }

  def data() = Action.async {
    implicit val timeout: Timeout = 25.seconds
    for {
      f <- {
        Logger.debug("sending StatusRequest to launcher")
        schedulerFSM ? GestaltMarathonLauncher.StatusRequest
      }
      resp = {
        Logger.debug("received StatusResponse from launcher")
        f.asInstanceOf[StatusResponse]
      }
    } yield Ok(Json.toJson(resp))
  }

  def shutdown(shutdownDB: Boolean) = Action.async {
    implicit val timeout: Timeout = 25.seconds
    Logger.info(s"received shutdown request: shutdownDB == ${shutdownDB}")
    val response = schedulerFSM ? GestaltMarathonLauncher.ShutdownRequest(shutdownDB = shutdownDB)
    response map {
      _ => Accepted(Json.obj("message" -> "Framework shutting down"))
    }
  }

  def restart() = Action {
    Logger.info(s"received restart request")
    schedulerFSM ! GestaltMarathonLauncher.LaunchServicesRequest
    Accepted(Json.obj("message" -> "Starting framework services"))
  }

}
