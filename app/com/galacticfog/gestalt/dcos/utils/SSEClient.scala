package com.galacticfog.gestalt.dcos.utils

import akka.actor.ActorRef
import akka.stream.scaladsl.{Sink, Framing, Source}
import akka.util.ByteString
import play.api.Logger
import play.api.libs.iteratee.{Iteratee, Enumeratee}
import play.api.libs.json.{Json, JsValue}
import play.api.libs.ws.{WSRequest, WSClient}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success, Try}


class SSEClient(wsclient: WSClient) {
  import SSEClient._

  private[this] def toJson(implicit exc: ExecutionContext) = Enumeratee.map[String] { str =>
    val event = """data: (.*)""".r
    for {
      m <- event findFirstMatchIn str
      data <- Try{m group 1}.toOption
      json <- Try{Json.parse(data)}.toOption
    } yield json
  }

  def openStream(request: WSRequest,
                 actorRef: ActorRef,
                 onClose: => Unit,
                 onError: PartialFunction[Throwable,Unit])(implicit exc: ExecutionContext): Unit =
  {
    val handle = new StreamHandle
    val quitter = Enumeratee.breakE[Array[Byte]] {_ => handle.disconnectRequested}

    val jsonToCallback: Iteratee[Option[JsValue],Unit] = Iteratee.foreach[Option[JsValue]] {
      js => js match {
        case Some(js) => ??? // onEvent(js)
        case None =>
      }
    }

    val toString: Enumeratee[Array[Byte], String] = Enumeratee.map[Array[Byte]] { bytes =>
      new String(bytes,"UTF-8")
    }

    val upToNewLine: Iteratee[String, String] =
      play.api.libs.iteratee.Traversable.splitOnceAt[String,Char](_ != '\n')  &>>
        Iteratee.consume()

    val lines: Enumeratee[String, String] = Enumeratee.grouped(upToNewLine)

    val myIt = quitter ><> toString ><> lines ><> toJson &>> jsonToCallback

//    val h: Future[Iteratee[Array[Byte], Unit]] = request
//      .withHeaders("Accept" -> "text/event-stream")
//      .withRequestTimeout(Duration.Inf)
//      .get( _ => myIt )
//    h onComplete { _ match {
//      case Success(s) =>
//        handle.p trySuccess ClosedByServer
//        onClose
//      case Failure(t) =>
//        handle.p failure t
//        onError.applyOrElse[Throwable,Unit](t,{t => Logger.debug("SSEClient unhandled error",t)})
//    } }
//    handle

    val source = streamResponse(request)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 2048, allowTruncation = true))
      .map { byteString =>
        Json.parse(byteString.utf8String)
      }

    val caller = Sink.actorRef(actorRef, ClosedByServer)

    source.runWith(caller)
  }

  def openStream(url: String,
                 onEvent: (JsValue) => Unit,
                 onClose: => Unit,
                 onError: PartialFunction[Throwable,Unit])(implicit exc: ExecutionContext): StreamHandle = {
    openStream(
      request = wsclient.url(url),
      onEvent = onEvent,
      onClose = onClose,
      onError = onError
    )
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


