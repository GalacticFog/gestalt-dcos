package com.galacticfog.gestalt.dcos.mesos

import java.util
import javax.inject.{Named, Inject}

import akka.actor.ActorRef
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver, Scheduler}
import play.api.{Logger => logger, Configuration}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.JavaConversions._

class GestaltMesosScheduler @Inject() (@Named("scheduler-actor") fsmActor: ActorRef) extends Scheduler {
  import org.apache.mesos.Protos._

  override def offerRescinded(schedulerDriver: SchedulerDriver, offerID: OfferID): Unit = {
    logger.info("offerRescinded: " + offerID.getValue)
  }

  override def disconnected(schedulerDriver: SchedulerDriver): Unit = {
    logger.info("DISCONNECTED")
  }

  override def reregistered(schedulerDriver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
    logger.info("re-registered with " + masterInfo.getPid)
  }

  override def slaveLost(schedulerDriver: SchedulerDriver, slaveID: SlaveID): Unit = {
    logger.info("SLAVE LOST: " + slaveID.getValue)
  }

  override def error(schedulerDriver: SchedulerDriver, s: String): Unit = {
    logger.error("error: " + s)
  }

  override def statusUpdate(schedulerDriver: SchedulerDriver, taskStatus: TaskStatus): Unit = {
    fsmActor ! taskStatus
    schedulerDriver.acknowledgeStatusUpdate(taskStatus)
  }

  override def frameworkMessage(schedulerDriver: SchedulerDriver,
                                executorID: ExecutorID,
                                slaveID: SlaveID,
                                bytes: Array[Byte]): Unit = {
    logger.info("received framework message from executor " + executorID.toString)
  }

  override def resourceOffers(schedulerDriver: SchedulerDriver, offers: util.List[Offer]): Unit = {
    offers.foreach(o => fsmActor ! o)
  }

  override def registered(schedulerDriver: SchedulerDriver,
                          frameworkID: FrameworkID,
                          masterInfo: MasterInfo): Unit = {
    logger.info("registered with " + masterInfo.getPid)
  }

  override def executorLost(schedulerDriver: SchedulerDriver,
                            executorID: ExecutorID,
                            slaveID: SlaveID,
                            i: Int): Unit = {
    logger.warn("lost executor " + executorID.toString)
  }
}

class GestaltSchedulerDriver @Inject() (config: Configuration,
                                        lifecycle: ApplicationLifecycle,
                                        scheduler: GestaltMesosScheduler) {
  import org.apache.mesos.Protos._

  logger.info("creating LambdaScheduler")

  val master = config.getString("mesos.master") getOrElse "master.mesos:5050"
  logger.info(s"registering with mesos-master: ${master}")

  val schedulerHostname = config.getString("hostname") getOrElse java.net.InetAddress.getLocalHost.getHostName
  logger.info(s"scheduler on: ${schedulerHostname}")

  val frameworkInfoBuilder = FrameworkInfo.newBuilder()
    .setName("gestalt")
    .setFailoverTimeout(60.seconds.toMillis)
    .setUser("root")
    .setRole("*")
    .setCheckpoint(true)
    .setHostname(schedulerHostname)

  val frameworkInfo = frameworkInfoBuilder.build()
  logger.info(s"scheduler on: ${schedulerHostname}")
  val implicitAcknowledgements = false

  val driver = new MesosSchedulerDriver( scheduler, frameworkInfo, master, implicitAcknowledgements )

  lifecycle.addStopHook { () =>
    Future{driver.stop()}
  }

  Future{driver.run()} map println

  def getDriver: SchedulerDriver = driver

}
