package controllers

import javax.inject._

import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.{BuildInfo, LauncherConfig}
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import views.html.index
import akka.pattern.ask
import akka.util.Timeout
import com.galacticfog.gestalt.dcos.launcher.LauncherFSM
import play.api.http.HeaderNames

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

@Singleton
class ApplicationController @Inject()(webJarAssets: WebJarAssets,
                                      @Named("scheduler-actor") schedulerFSM: ActorRef,
                                      launcherConfig: LauncherConfig)
                                     (implicit ec: ExecutionContext) extends Controller {


  val logger = Logger(this.getClass)

  private def fromAddress(implicit request: RequestHeader) = request.headers.get(HeaderNames.X_FORWARDED_HOST) getOrElse request.remoteAddress

  def health = Action {
    Ok(Json.obj(
      "status" -> "healthy"
    ))
  }

  def dashboard = Action {
    Ok(index.render(webJarAssets, launcherConfig.database.provision, launcherConfig.gestaltFrameworkVersion getOrElse BuildInfo.version))
  }

  def data() = Action.async { implicit request =>
    implicit val timeout: Timeout = 25.seconds
    for {
      f <- {
        logger.debug(s"sending StatusRequest to launcher from ${fromAddress}")
        schedulerFSM ? LauncherFSM.Messages.StatusRequest
      }
      resp = {
        logger.debug(s"received StatusResponse from launcher for ${fromAddress}")
        f.asInstanceOf[LauncherFSM.Messages.StatusResponse]
      }
    } yield Ok(Json.toJson(resp))
  }

  def shutdown(shutdownDB: Boolean) = Action.async { implicit request =>
    implicit val timeout: Timeout = 25.seconds
    logger.info(s"received shutdown request: shutdownDB == ${shutdownDB} ${fromAddress}")
    val response = schedulerFSM ? LauncherFSM.Messages.ShutdownRequest(shutdownDB = shutdownDB)
    response map {
      _ => Accepted(Json.obj("message" -> s"Framework shutting down for ${fromAddress}"))
    }
  }

  def restart() = Action { implicit request =>
    logger.info(s"received restart request from ${fromAddress}")
    schedulerFSM ! LauncherFSM.Messages.LaunchServicesRequest
    Accepted(Json.obj("message" -> "Starting framework services"))
  }

}
