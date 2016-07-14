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

  override def disconnected(schedulerDriver: SchedulerDriver): Unit = {
    logger.warn("DummyScheduler disconnected from Mesos master")
  }

  override def reregistered(schedulerDriver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
    logger.info(s"DummyScheduler re-registered with Mesos master: ${masterInfo.getId} @ ${masterInfo.getAddress}:${masterInfo.getPort}")
  }


  override def error(schedulerDriver: SchedulerDriver, s: String): Unit = {
    logger.warn(s"error from Mesos scheduler driver: ${s}")
  }

  override def resourceOffers(schedulerDriver: SchedulerDriver, list: util.List[Offer]): Unit = {
    val filter = Filters.newBuilder().setRefuseSeconds(60).build
    list.foreach {o =>
      logger.trace(s"declining offer ${o.getId.getValue}")
      schedulerDriver.declineOffer(o.getId, filter)
    }
  }

  override def registered(schedulerDriver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo): Unit = {
    logger.info(s"DummyScheduler registered as framework ${frameworkId.getValue} with Mesos master: ${masterInfo.getId} @ ${masterInfo.getAddress.getIp}:${masterInfo.getPort}")
  }

  override def executorLost(schedulerDriver: SchedulerDriver, executorID: ExecutorID, slaveID: SlaveID, i: Int): Unit = {}
  override def offerRescinded(schedulerDriver: SchedulerDriver, offerID: OfferID): Unit = {}
  override def slaveLost(schedulerDriver: SchedulerDriver, slaveID: SlaveID): Unit = {}
  override def statusUpdate(schedulerDriver: SchedulerDriver, taskStatus: TaskStatus): Unit = {}
  override def frameworkMessage(schedulerDriver: SchedulerDriver, executorID: ExecutorID, slaveID: SlaveID, bytes: Array[Byte]): Unit = {}

}

class GestaltSchedulerDriver @Inject() (config: Configuration,
                                        lifecycle: ApplicationLifecycle,
                                        scheduler: DummyScheduler,
                                        @Named("scheduler-actor") schedulerActor: ActorRef) {
  import org.apache.mesos.Protos._

  logger.info("creating GestaltSchedulerDriver for DummyScheduler")

  val master = config.getString("mesos.master") getOrElse "master.mesos:5050"
  logger.info(s"attempting to register with mesos-master @ ${master}")

  val schedulerHostname = config.getString("hostname") getOrElse java.net.InetAddress.getLocalHost.getHostName
  logger.info(s"scheduler running @ ${schedulerHostname}")

  val schedulerName = config.getString("scheduler.name") getOrElse "gestalt-framework-scheduler"

  val frameworkInfoBuilder = FrameworkInfo.newBuilder()
    .setName(schedulerName)
    .setFailoverTimeout(60 /* seconds */)
    .setUser("root")
    .setRole("*")
    .setCheckpoint(false)
    .setWebuiUrl(s"http://${schedulerHostname}:${sys.env.get("PORT0").getOrElse(9000)}/")
    .setHostname(schedulerHostname)

  val frameworkInfo = frameworkInfoBuilder.build()
  val implicitAcknowledgements = false

  val driver = new MesosSchedulerDriver( scheduler, frameworkInfo, master, implicitAcknowledgements )

  lifecycle.addStopHook { () =>
    Future{driver.stop(false)}
  }

  logger.info("Starting MesosSchedulerDriver: " + driver.start())

  schedulerActor ! LaunchServicesRequest

  def getDriver: SchedulerDriver = driver

}

