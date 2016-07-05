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
case object LaunchingTasks extends State
case object RunningTasks extends State

case class Data(waiting: Map[String,TaskInfo.Builder],
                matches: Seq[OfferMatch],
                launched: Map[String,TaskInfo],
                running: Map[String,TaskInfo])

case class OfferMatch(name: String, offer: Offer, taskInfo: TaskInfo)

final case object MatchesLaunched

case object Data {
  def init(gtf: GestaltTaskFactory) = Data(
    waiting = gtf.allTasks.map(n => n -> gtf.getTaskInfo(n)).toMap,
    matches = Seq.empty,
    launched = Map.empty,
    running = Map.empty
  )
}

class SchedulerFSM @Inject()(configuration: Configuration,
                             taskFactory: GestaltTaskFactory,
                             gsd: GestaltSchedulerDriver) extends LoggingFSM[State,Data] {

  def matchOffer(o: Offer, d: Data): this.State = {
    val found = d.waiting.find {
      case (srvName,taskInfo) => taskInfo.getResourcesList.asScala.forall(
        taskResource => o.getResourcesList.asScala.exists(
          offerResource => offerResource.getType == taskResource.getType &&
            offerResource.getScalar.getValue >= taskResource.getScalar.getValue
        )
      )
    }
    found match {
      case Some((srvName,taskInfoBuilder)) =>
        val taskInfo = taskFactory.complete(taskInfoBuilder, o)
        goto(MatchedTasks) using d.copy(
          waiting = d.waiting - srvName,
          matches = d.matches :+ OfferMatch(srvName,o,taskInfo)
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
    val taskInfo = d.launched.find(_._2.getTaskId == ts.getTaskId) orElse
                  d.running.find(_._2.getTaskId == ts.getTaskId)
    taskInfo match {
      case None =>
        log.warning(s"received update for unknown task: ${ts.getTaskId.getValue}")
        stay
      case Some((srvName,taskInfo)) =>
        val newData = if (ts.getState == TaskState.TASK_RUNNING) d.copy(
          waiting = d.waiting - srvName,
          launched = d.launched - srvName,
          running = d.running + (srvName -> taskInfo)
        ) else if (isStopped(ts.getState)) d.copy(
          waiting = d.waiting + (srvName -> TaskInfo.newBuilder(taskInfo)),
          launched = d.launched - srvName,
          running = d.running - srvName
        ) else d
        if (newData.matches.nonEmpty) {
          assert(stateName == MatchedTasks)
          goto(MatchedTasks) using newData
        } else if (newData.waiting.isEmpty && newData.launched.isEmpty) {
          goto(RunningTasks) using newData
        } else {
          goto(SeekingOffers) using newData
        }
    }
  }

  startWith(SeekingOffers, Data.init(taskFactory))

  when(SeekingOffers) {
    case Event(o: Offer, d) => matchOffer(o, d)
  }

  when(MatchedTasks) {
    case Event(MatchesLaunched, d) =>
      val newD = d.copy(
        matches = Seq.empty,
        launched = d.launched ++ d.matches.map(m => m.name -> m.taskInfo)
      )
      if (newD.waiting.isEmpty) goto(LaunchingTasks) using newD
      else goto(SeekingOffers) using newD
  }

  when(LaunchingTasks) {
    case Event(ts: TaskStatus, d) =>
      updateTask(ts,d)
  }

  when(RunningTasks) {
    case Event(ts: TaskStatus, d) =>
      updateTask(ts,d)
  }

  whenUnhandled {
    case Event(ts: TaskStatus, d) =>
      updateTask(ts,d)
    case Event(o: Offer, d) =>
      log.info("rejecting offer {}", o.getId.getValue)
      val filters = Filters.newBuilder().setRefuseSeconds(60)
      gsd.driver.declineOffer(o.getId, filters.build)
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

