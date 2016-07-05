package com.galacticfog.gestalt.dcos.actors

import javax.inject.Inject
import com.galacticfog.gestalt.dcos.GestaltTaskFactory
import com.galacticfog.gestalt.dcos.mesos.GestaltSchedulerDriver

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

import akka.actor._
import org.apache.mesos.Protos._
import play.api.Configuration

sealed trait State
case object SeekingOffers extends State
case object MatchedTasks extends State
case object UpdatingTasks extends State

case class Data(waiting: Seq[String],
                matches: Seq[OfferMatch],
                active: Map[String,TaskStatus])

case class OfferMatch(name: String, offer: Offer, taskInfo: TaskInfo)
case object ServiceStatusRequest
case class ServiceStatusResponse(states: Map[String,String])

final case object MatchesLaunched

case object Data {
  def init(gtf: GestaltTaskFactory) = Data(
    waiting = gtf.allTasks,
    matches = Seq.empty,
    active = Map.empty
  )
}

class SchedulerFSM @Inject()(configuration: Configuration,
                             taskFactory: GestaltTaskFactory,
                             gsd: GestaltSchedulerDriver) extends LoggingFSM[State,Data] {

  def matchOffer(o: Offer, d: Data): this.State = {
    log.info("attempting to match offer {}",o.getId.getValue)
    val waiting = d.waiting.map(taskFactory.getAppSpec)
    val found = waiting.find { app =>
      log.info("checking app: {}",app.name)
      o.getResourcesList.asScala.exists(
          offerResource => offerResource.getName == "cpus" && offerResource.getScalar.getValue >= app.cpu
      ) && o.getResourcesList.asScala.exists(
        offerResource => offerResource.getName == "mem" && offerResource.getScalar.getValue >= app.mem
      )
    }
    found match {
      case Some(app) =>
        log.info("MATCH: {} against {}", app.name, o.getId.getValue)
        val taskInfo = taskFactory.toTaskInfo(app, o)
        goto(MatchedTasks) using d.copy(
          waiting = d.waiting.diff(Seq(app.name)),
          matches = d.matches :+ OfferMatch(app.name,o,taskInfo)
        )
      case None =>
        log.info("rejecting offer {}", o.getId.getValue)
        gsd.driver.declineOffer(o.getId)
        stay
    }
  }

  def isStopped(state: TaskState): Boolean = {
    Set(
      TaskState.TASK_ERROR,
      TaskState.TASK_FAILED,
      TaskState.TASK_FINISHED,
      TaskState.TASK_KILLED,
      TaskState.TASK_LOST
    ).contains(state)
  }

  def updateTask(ts: TaskStatus, d: Data): this.State = {
    val srvName = ts.getTaskId.getValue.split("-",1).head
    val newData = if (isStopped(ts.getState)) d.copy(
      waiting = d.waiting :+ srvName,
      active = d.active + (srvName -> ts)
    ) else d.copy(
      active = d.active + (srvName -> ts)
    )
    if (newData.matches.nonEmpty) {
      assert(stateName == MatchedTasks)
      goto(MatchedTasks) using newData
    } else if (newData.waiting.nonEmpty) {
      goto(SeekingOffers) using newData
    } else {
      goto(UpdatingTasks) using newData
    }
  }

  startWith(SeekingOffers, Data.init(taskFactory))

  when(SeekingOffers) {
    case Event(o: Offer, d) => matchOffer(o, d)
  }

  when(MatchedTasks) {
    case Event(MatchesLaunched, d) =>
      val newD = d.copy(
        matches = Seq.empty
      )
      if (newD.waiting.isEmpty) goto(UpdatingTasks) using newD
      else goto(SeekingOffers) using newD
    case Event(o: Offer, d) =>
      log.info("rejecting offer {}", o.getId.getValue)
      gsd.driver.declineOffer(o.getId)
      stay
  }

  when(UpdatingTasks) {
    case Event(o: Offer, d) =>
      log.info("rejecting offer {}", o.getId.getValue)
      val filters = Filters.newBuilder().setRefuseSeconds(60)
      gsd.driver.declineOffer(o.getId, filters.build)
      stay
  }

  whenUnhandled {
    case Event(ts: TaskStatus, d) =>
      updateTask(ts,d)
    case Event(ServiceStatusRequest,d) =>
      sender ! ServiceStatusResponse(
        d.waiting.map(name => (name -> "WAITING")).toMap
        ++ d.matches.map(o => (o.name -> "MATCHED_OFFER")).toMap
        ++ d.active.mapValues(_.getState.getValueDescriptor.getName)
      )
      stay
  }

  onTransition {
    case SeekingOffers -> MatchedTasks =>
      nextStateData.matches foreach { om =>
        log.info("launching '{}' against offer {}", om.name, om.offer.getId.getValue)
        gsd.driver.launchTasks(
          Seq(om.offer.getId).asJava,
          Seq(om.taskInfo).asJava
        )
      }
      self ! MatchesLaunched
  }

  initialize()

}

