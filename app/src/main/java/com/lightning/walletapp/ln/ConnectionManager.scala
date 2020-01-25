package com.lightning.walletapp.ln

import scala.concurrent._
import scala.concurrent.duration._
import com.lightning.walletapp.ln.wire._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.Features._
import rx.lang.scala.{Subscription, Observable => Obs}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import com.lightning.walletapp.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.ByteVector
import java.net.Socket


object ConnectionManager {
  var listeners = Set.empty[ConnectionListener]
  val workers = new ConcurrentHashMap[PublicKey, Worker].asScala

  protected[this] val events = new ConnectionListener {
    override def onMessage(nodeId: PublicKey, msg: LightningMessage) = for (lst <- listeners) lst.onMessage(nodeId, msg)
    override def onHostedMessage(ann: NodeAnnouncement, msg: HostedChannelMessage) = for (lst <- listeners) lst.onHostedMessage(ann, msg)
    override def onOperational(nodeId: PublicKey, isCompat: Boolean) = for (lst <- listeners) lst.onOperational(nodeId, isCompat)
    override def onDisconnect(nodeId: PublicKey) = for (lst <- listeners) lst.onDisconnect(nodeId)
  }

  def connectTo(ann: NodeAnnouncement, keyPair: KeyPair, notify: Boolean) = synchronized {
    // We still need to reconnect in case if nodeAnnouncement is the same but keyPair is different
    // reconnect is carried out by replacing a worker, note that an old one will trigger onDisconnect

    workers get ann.nodeId match {
      case None => workers(ann.nodeId) = Worker(ann, keyPair)
      case Some(worker) if worker.keyPair != keyPair => workers(ann.nodeId) = Worker(ann, keyPair)
      case Some(worker) => events.onOperational(worker.ann.nodeId, isCompat = true)
    }
  }

  case class Worker(ann: NodeAnnouncement, keyPair: KeyPair, buffer: Bytes = new Bytes(1024), sock: Socket = new Socket) {
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    private var ourLastPing = Option.empty[Ping]
    private var pinging: Subscription = _

    def disconnect: Unit = try sock.close catch none
    val handler: TransportHandler = new TransportHandler(keyPair, ann.nodeId) {
      def handleEncryptedOutgoingData(data: ByteVector) = try sock.getOutputStream write data.toArray catch handleError
      def handleDecryptedIncomingData(data: ByteVector) = Tuple2(LightningMessageCodecs deserialize data, ourLastPing) match {
        case (init: Init, _) => events.onOperational(isCompat = isNodeSupported(init.localFeatures) && dataLossProtect(init.localFeatures), nodeId = ann.nodeId)
        case Ping(replyLength, _) \ _ if replyLength > 0 && replyLength <= 65532 => handler process Pong(ByteVector fromValidHex "00" * replyLength)
        case Pong(randomData) \ Some(ourPing) if randomData.size == ourPing.pongLength => ourLastPing = None
        case (message: HostedChannelMessage, _) => events.onHostedMessage(ann, message)
        case (message, _) => events.onMessage(ann.nodeId, message)
      }

      def handleEnterOperationalState = {
        handler process Init(LNParams.globalFeatures, LNParams.localFeatures)
        pinging = Obs.interval(15.seconds).map(_ => random.nextInt(10) + 1) subscribe { length =>
          val ourNextPing = Ping(data = ByteVector.view(random getBytes length), pongLength = length)
          if (ourLastPing.isEmpty) handler process ourNextPing else disconnect
          ourLastPing = Some(ourNextPing)
        }
      }

      // Just disconnect immediately in all cases
      def handleError = { case _ => disconnect }
    }

    val thread = Future {
      val theOne = ann.unsafeFirstAddress
      sock.connect(theOne.get, 7500)
      handler.init

      while (true) {
        val length = sock.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process ByteVector.view(buffer take length)
      }
    }

    thread onComplete { _ =>
      workers.remove(ann.nodeId)
      events.onDisconnect(ann.nodeId)
      try pinging.unsubscribe catch none
    }
  }
}

class ConnectionListener {
  def onOpenOffer(nodeId: PublicKey, msg: OpenChannel): Unit = none
  def onMessage(nodeId: PublicKey, msg: LightningMessage): Unit = none
  def onHostedMessage(ann: NodeAnnouncement, msg: HostedChannelMessage): Unit = none
  def onOperational(nodeId: PublicKey, isCompat: Boolean): Unit = none
  def onDisconnect(nodeId: PublicKey): Unit = none
}