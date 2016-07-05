package com.galacticfog.gestalt.dcos.mesos

import java.util
import javax.inject.{Named, Inject}

import akka.actor.ActorRef
import com.galacticfog.gestalt.dcos.marathon.LaunchServicesRequest
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver, Scheduler}
import play.api.{Logger => logger, Configuration}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.JavaConversions._



class DummyScheduler @Inject() extends Scheduler {
  override def offerRescinded(schedulerDriver: SchedulerDriver, offerID: OfferID): Unit = {}

  override def disconnected(schedulerDriver: SchedulerDriver): Unit = {}

  override def reregistered(schedulerDriver: SchedulerDriver, masterInfo: MasterInfo): Unit = {}

  override def slaveLost(schedulerDriver: SchedulerDriver, slaveID: SlaveID): Unit = {}

  override def error(schedulerDriver: SchedulerDriver, s: String): Unit = {}

  override def statusUpdate(schedulerDriver: SchedulerDriver, taskStatus: TaskStatus): Unit = {}

  override def frameworkMessage(schedulerDriver: SchedulerDriver, executorID: ExecutorID, slaveID: SlaveID, bytes: Array[Byte]): Unit = {}

  override def resourceOffers(schedulerDriver: SchedulerDriver, list: util.List[Offer]): Unit = {
    val filter = Filters.newBuilder().setRefuseSeconds(60).build
    list.foreach(o => schedulerDriver.declineOffer(o.getId, filter))
  }

  override def registered(schedulerDriver: SchedulerDriver, frameworkID: FrameworkID, masterInfo: MasterInfo): Unit = {}

  override def executorLost(schedulerDriver: SchedulerDriver, executorID: ExecutorID, slaveID: SlaveID, i: Int): Unit = {}
}

class GestaltSchedulerDriver @Inject() (config: Configuration,
                                        lifecycle: ApplicationLifecycle,
                                        scheduler: DummyScheduler,
                                        @Named("scheduler-actor") schedulerActor: ActorRef) {
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
    .setCheckpoint(false)
    .setHostname(schedulerHostname)

  val frameworkInfo = frameworkInfoBuilder.build()
  logger.info(s"scheduler on: ${schedulerHostname}")
  val implicitAcknowledgements = false

  val driver = new MesosSchedulerDriver( scheduler, frameworkInfo, master, implicitAcknowledgements )

  lifecycle.addStopHook { () =>
    Future{driver.stop()}
  }

  Future{driver.run()} map println

  schedulerActor ! LaunchServicesRequest

  def getDriver: SchedulerDriver = driver

}
