package com.galacticfog.gestalt.dcos.utils

import javax.inject.Inject

import akka.NotUsed
import akka.actor.{ActorSystem, ActorRef}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushPullStage}
import akka.util.ByteString
import play.api.libs.json.{Json}
import play.api.libs.ws.{WSRequest, WSClient}
import scala.concurrent.{ExecutionContext, Promise, Future}


class SSEClient @Inject() (wsclient: WSClient)(implicit system: ActorSystem) {
  import SSEClient._

  def openStream(request: WSRequest,
                 actorRef: ActorRef)
                 (implicit exc: ExecutionContext): Unit =
  {
    implicit val mat = ActorMaterializer()
    val source = streamResponse(request)
      .via(mesosHttp())
      .map { byteString =>
        Json.parse(byteString.utf8String)
      }

    val caller = Sink.actorRef(actorRef, ClosedByServer)

    source.runWith(caller)
  }

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

object SSEClient {
  sealed trait CloseReason
  case object ClosedByServer extends CloseReason
  case object ClosedByClient extends CloseReason

  class StreamHandle {
    private[SSEClient] var disconnectRequested: Boolean = false
    private[SSEClient] val p = Promise[CloseReason]
    def disconnect() = {
      p trySuccess ClosedByClient
      disconnectRequested = true
    }
    def isConnected: Boolean = ! p.isCompleted
    def reasonClosed: Future[CloseReason] = p.future
  }

  private def streamResponse(request: WSRequest) = Source.fromFuture(request.stream()).flatMapConcat(_.body)

}


