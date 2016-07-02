package com.galacticfog.gestalt.dcos.mesos

import java.util
import javax.inject.Inject

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver, Scheduler}
import org.joda.time.LocalTime
import play.api.libs.json.Json
import play.api.{Logger => logger, Configuration}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.JavaConversions._

class GestaltMesosScheduler extends Scheduler {
  import org.apache.mesos.Protos._

  override def offerRescinded(schedulerDriver: SchedulerDriver, offerID: OfferID): Unit = {
    logger.info("offerRescinded: " + offerID)
  }

  override def disconnected(schedulerDriver: SchedulerDriver): Unit = {
    logger.info("DISCONNECTED")
  }

  override def reregistered(schedulerDriver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
    logger.info("re-registered with " + masterInfo.toString)
  }

  override def slaveLost(schedulerDriver: SchedulerDriver, slaveID: SlaveID): Unit = {
    logger.info("SLAVE LOST: " + slaveID)
  }

  override def error(schedulerDriver: SchedulerDriver, s: String): Unit = {
    logger.error("error: " + s)
  }

  override def statusUpdate(schedulerDriver: SchedulerDriver, taskStatus: TaskStatus): Unit = {
    logger.info("task status update: " + taskStatus.toString)
  }

  override def frameworkMessage(schedulerDriver: SchedulerDriver,
                                executorID: ExecutorID,
                                slaveID: SlaveID,
                                bytes: Array[Byte]): Unit = {
    logger.info("received framework message from executor " + executorID.toString)
  }

  override def resourceOffers(schedulerDriver: SchedulerDriver, offers: util.List[Offer]): Unit = {
    logger.info("received offer from: ")
    offers.foreach(o => logger.info("> " + o.getSlaveId.toString))
  }

  override def registered(schedulerDriver: SchedulerDriver,
                          frameworkID: FrameworkID,
                          masterInfo: MasterInfo): Unit = {
    logger.info("registered with " + masterInfo.toString)
  }

  override def executorLost(schedulerDriver: SchedulerDriver,
                            executorID: ExecutorID,
                            slaveID: SlaveID,
                            i: Int): Unit = {
    logger.warn("lost executor " + executorID.toString)
  }
}

class GestaltHttpSchedulerDriver @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)
                                           (implicit system: ActorSystem) {

  import mesosphere.mesos.protos.{FrameworkInfo, FrameworkID}

  implicit val materializer = ActorMaterializer()

  val master = config.getString("mesos.master") getOrElse "master.mesos:5050"
  val (masterHost,masterPort) = master.split(":") match {
    case Array(host,port) => (host,port.toInt)
    case Array(host) => (host,5050)
    case _ => throw new RuntimeException("improperly formatted mesos.master")
  }

  logger.info(s"registering with master: ${masterHost}:${masterPort}")

  implicit val frameworkIdFmt = Json.format[FrameworkID]
  implicit val frameworkFmt = Json.format[FrameworkInfo]

  val registration = Json.obj(
    "type" -> "SUBSCRIBE",
    "subscribe" -> Json.obj(
      "framework_info" -> Json.obj(
        "user" -> "root",
        "name" -> "gestalt-dcos",
        "checkpoint" -> true,
        "failover_timeout" -> 60
      )
    )
  )

}

class GestaltSchedulerDriver @Inject() (config: Configuration, lifecycle: ApplicationLifecycle) {

  import org.apache.mesos.Protos._

  logger.info("creating LambdaScheduler")
  val scheduler = new GestaltMesosScheduler

  val master = config.getString("mesos.master") getOrElse "master.mesos:5050"
  logger.info(s"registering with mesos-master: ${master}")

  val schedulerHostname = config.getString("hostname") getOrElse java.net.InetAddress.getLocalHost.getHostName
  logger.info(s"scheduler on: ${schedulerHostname}")

  val frameworkInfoBuilder = FrameworkInfo.newBuilder()
    .setName("gestalt")
    .setFailoverTimeout(60.seconds.toMillis)
    .setUser("")
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

}
