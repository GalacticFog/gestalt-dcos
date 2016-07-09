package com.galacticfog.gestalt.dcos.mesos

import java.util

import akka.actor.Actor.Receive
import org.apache.mesos.Protos._
import org.apache.mesos.{Scheduler, SchedulerDriver}
import play.api.libs.json.Json
import play.api.{Logger => logger}

import akka.NotUsed
import akka.actor.{ActorLogging, Actor}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushPullStage}
import akka.util.ByteString
import play.api.libs.ws.{WSClient}

class GestaltHttpSchedulerDriver(wsclient: WSClient, master: String, user: String, frameworkName: String, checkpoint: Boolean = false, failoverTimeoutSeconds: Int = 60, scheduler: Scheduler) extends SchedulerDriver with Actor with ActorLogging {

  val (masterHost,masterPort) = master.split(":") match {
    case Array(host,port) => (host,port.toInt)
    case Array(host) => (host,5050)
    case _ => throw new RuntimeException("improperly formatted mesos master: expecting 'hostname[:port]'")
  }

  override def declineOffer(offerID: OfferID, filters: Filters): Status = ???

  override def declineOffer(offerID: OfferID): Status = ???

  override def launchTasks(collection: util.Collection[OfferID], collection1: util.Collection[TaskInfo], filters: Filters): Status = ???

  override def launchTasks(collection: util.Collection[OfferID], collection1: util.Collection[TaskInfo]): Status = ???

  override def launchTasks(offerID: OfferID, collection: util.Collection[TaskInfo], filters: Filters): Status = ???

  override def launchTasks(offerID: OfferID, collection: util.Collection[TaskInfo]): Status = ???

  override def stop(b: Boolean): Status = ???

  override def stop(): Status = ???

  override def killTask(taskID: TaskID): Status = ???

  override def acceptOffers(collection: util.Collection[OfferID], collection1: util.Collection[Offer.Operation], filters: Filters): Status = ???

  override def acknowledgeStatusUpdate(taskStatus: TaskStatus): Status = ???

  override def requestResources(collection: util.Collection[Request]): Status = ???

  override def sendFrameworkMessage(executorID: ExecutorID, slaveID: SlaveID, bytes: Array[Byte]): Status = ???

  override def join(): Status = ???

  override def reconcileTasks(collection: util.Collection[TaskStatus]): Status = ???

  override def reviveOffers(): Status = ???

  override def suppressOffers(): Status = ???

  override def run(): Status = ???

  override def abort(): Status = ???

  override def start(): Status = {
    implicit val materializer = ActorMaterializer()

    logger.info(s"GestaltHttpSchedulerDriver: registering with master: ${masterHost}:${masterPort}")

    val registration = Json.obj(
      "type" -> "SUBSCRIBE",
      "subscribe" -> Json.obj(
        "framework_info" -> Json.obj(
          "user" -> user,
          "name" -> frameworkName,
          "checkpoint" -> checkpoint,
          "failover_timeout" -> failoverTimeoutSeconds
        )
      )
    )

    val request = wsclient
      .url(s"http://${masterHost}:${masterPort}/api/v1/scheduler")
      .withMethod("POST")
      .withBody(registration)

    val src = Source.fromFuture(request.stream()).flatMapConcat(_.body)

    val source = src
      .via(GestaltHttpSchedulerDriver.mesosHttp())
      .map { byteString =>
        Json.parse(byteString.utf8String)
      }

    val caller = Sink.actorRef(this.self, GestaltHttpSchedulerDriver.ClosedByServer)

    source.runWith(caller)

    Status.DRIVER_RUNNING
  }

  override def receive: Receive = ???

}

object GestaltHttpSchedulerDriver {

  sealed trait CloseReason
  case object ClosedByServer extends CloseReason
  case object ClosedByClient extends CloseReason


  def mesosHttp(): Flow[ByteString, ByteString, NotUsed] = {
    Flow[ByteString].transform(() => new MesosHTTPFramingStage())
      .named("mesosHttpFramingStage")
  }

  private final class MesosHTTPFramingStage() extends PushPullStage[ByteString, ByteString] {
    private var buffer = ByteString.empty
    private var frameSize = Int.MaxValue
    private val minimumChunkSize = 4

    private def tryPull(ctx: Context[ByteString]): SyncDirective =
      if (ctx.isFinishing) ctx.fail(new FramingException("Stream finished but there was a truncated final frame in the buffer"))
      else ctx.pull()

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      buffer ++= chunk
      doParse(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = doParse(ctx)

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()

    private def doParse(ctx: Context[ByteString]): SyncDirective = {
      def emitFrame(ctx: Context[ByteString]): SyncDirective = {
        val parsedFrame = buffer.take(frameSize).compact
        buffer = buffer.drop(frameSize)
        frameSize = Int.MaxValue
        if (ctx.isFinishing && buffer.isEmpty) ctx.pushAndFinish(parsedFrame)
        else ctx.push(parsedFrame)
      }

      val bufSize = buffer.size
      if (bufSize >= frameSize) emitFrame(ctx)
      else if (bufSize >= minimumChunkSize) {
        val parsedLength: Int = 0 // TODO
        frameSize = parsedLength + minimumChunkSize
        if (bufSize >= frameSize) emitFrame(ctx)
        else tryPull(ctx)
      } else tryPull(ctx)
    }

    override def postStop(): Unit = buffer = null
  }

}


