package akkasmpp.actors

import akka.actor.{Props, Stash, ActorRef, Deploy, Actor, ActorLogging}
import java.net.InetSocketAddress
import akkasmpp.protocol.{OctetString, COctetString, DeliverSmResp, DeliverSm, GenericNack, SubmitSmResp, CommandStatus, EnquireLinkResp, BindRespLike, BindReceiver, BindTransceiver, AtomicIntegerSequenceNumberGenerator, Priority, DataCodingScheme, RegisteredDelivery, NullTime, EsmClass, ServiceType, SubmitSm, EnquireLink, NumericPlanIndicator, TypeOfNumber, BindTransmitter, Pdu, SmppFramePipeline}
import akka.io.{TcpReadWriteAdapter, TcpPipelineHandler, Tcp, IO}
import akka.io.TcpPipelineHandler.WithinActorContext
import akkasmpp.protocol.NumericPlanIndicator.NumericPlanIndicator
import akkasmpp.protocol.TypeOfNumber.TypeOfNumber
import akkasmpp.protocol.DataCodingScheme.DataCodingScheme
import akkasmpp.protocol.CommandStatus.CommandStatus
import akkasmpp.protocol.SmppTypes.SequenceNumber
import akkasmpp.actors.SmppClient.Did
import scala.concurrent.duration.{FiniteDuration, Duration}

/**
 * Basic ESME behaviors
 */

object SmppClient {

  def props(config: SmppClientConfig) = Props(classOf[SmppClient], config)

  object Implicits {
    implicit def stringAsDid(s: String) = Did(s)
  }

  abstract class BindMode
  object Transceiver extends BindMode
  object Transmitter extends BindMode
  object Receiver extends BindMode

  case class Did(number: String, `type`: TypeOfNumber = TypeOfNumber.International,
                 npi: NumericPlanIndicator = NumericPlanIndicator.E164)
  abstract class Command
  case class Bind(systemId: String, password: String, systemType: Option[String] = None,
                  mode: BindMode = Transceiver, addrTon: TypeOfNumber = TypeOfNumber.International,
                  addrNpi: NumericPlanIndicator = NumericPlanIndicator.E164) extends Command

  /**
   * Command to send a message through the SmppClient.
   * @param content Message to send. Will be translated into a Seq[SubmitSm] messages
   *                with the right ESM class and UDH headers for concat.
   * @param encoding None for default encoding (figures it out) or manually set an encoding.
   */
  case class SendMessage(content: String, to: Did, from: Did, encoding: Option[DataCodingScheme] = None) extends Command

  /**
   * Incoming message over the SMPP connection
   * @param content Decoded content of the message (assumes not binary)
   * @param to Who the message is intended for
   * @param from Who the message came from
   */
  case class ReceiveMessage(content: String, to: Did, from: Did) extends Command

  /**
   * Used internally
   */
  case object SendEnquireLink extends Command

  abstract class Response

  /**
   * Response for SendMessage, sent when submit_sm_resp is received
   * @param results Tuple of (Status, MessageId)
   *              CommandStatus is 0 for success, otherwise a failure
   *              MessageId is Some(messageId) if successful, otherwise, None
   *              MessageId is used later for delivery receipts
   */
  case class SendMessageAck(results: Seq[(CommandStatus, Option[String])]) extends Response
}

case class SmppClientConfig(bindTo: InetSocketAddress, enquireLinkTimer: Duration = Duration.Inf)

/**
 * Example SmppClient using the PDU layer
 */
class SmppClient(config: SmppClientConfig) extends Actor with ActorLogging with Stash {

  type SmppPipeLine = TcpPipelineHandler.Init[WithinActorContext, Pdu, Pdu]

  import akka.io.Tcp._
  import scala.concurrent.duration._
  import context.system

  val manager = IO(Tcp)

  val sequenceNumberGen = new AtomicIntegerSequenceNumberGenerator
  var window = Map[SequenceNumber, ActorRef]()

  log.debug(s"Connecting to server at " + config.bindTo.toString)
  manager ! Connect(config.bindTo, timeout = Some(3.seconds))

  override def postStop() = {
    manager ! Close
  }

  def receive = connecting

  def connecting: Actor.Receive = {
    case CommandFailed(_: Connect) =>
      log.error("Network connection failed")
      context stop self
    case c @ Connected(remote, local) =>
      log.debug(s"Connection established to server at $remote")

      val pipeline = TcpPipelineHandler.withLogger(log, new SmppFramePipeline >> new TcpReadWriteAdapter)
      val handler = context.actorOf(TcpPipelineHandler.props(pipeline, sender, self).withDeploy(Deploy.local))
      context.watch(handler)
      sender ! Tcp.Register(handler)
      unstashAll()
      context.become(bind(pipeline, handler))
    case _ => stash()
  }

  def bind(wire: SmppPipeLine, connection: ActorRef): Actor.Receive = {
    case SmppClient.Bind(systemId, password, systemType, mode, addrTon, addrNpi) =>
      val bindFactory = mode match {
        case SmppClient.Transceiver => BindTransceiver(_, _, _, _, _, _, _)
        case SmppClient.Receiver => BindReceiver(_, _, _, _, _, _, _)
        case SmppClient.Transmitter => BindTransmitter(_, _, _, _, _, _, _)
      }
      implicit val encoding = java.nio.charset.Charset.forName("UTF-8")
      val cmd = bindFactory(sequenceNumberGen.next, new COctetString(systemId), new COctetString(password),
        new COctetString(systemType.getOrElse("")), 0x34, addrTon, addrNpi)
      log.info(s"Making bind request $cmd")
      connection ! wire.Command(cmd)
      unstashAll()
      context.become(binding(wire, connection))
    case _ => stash()
  }

  def binding(wire: SmppPipeLine, connection: ActorRef): Actor.Receive = {
    // Future improvement: Type tags to ensure the response is the same as the request?
    case wire.Event(p: BindRespLike) =>
      if (p.commandStatus == CommandStatus.ESME_ROK) {
        unstashAll()
        log.info(s"Bound: $p")
        // start timers
        config.enquireLinkTimer match {
          case f: FiniteDuration =>
            log.debug("Starting EnquireLink loop")
            context.system.scheduler.schedule(f, f, self, SmppClient.SendEnquireLink)(context.dispatcher)
          case _ =>
        }

        context.become(bound(wire, connection))
      } else {
        throw new Exception(s"bind failed! $p")
      }
    case c: SmppClient.Command => stash()
    case x => log.info(s"unexpected event! $x")

  }

  import SmppClient.{SendMessage, SendEnquireLink}
  def bound(wire: SmppPipeLine, connection: ActorRef): Actor.Receive = {
    case SendMessage(msg, to, from, encoding) =>
      // XXX: Support concat and non-ascii
      val body = msg.getBytes("ASCII")
      val seqNum = sequenceNumberGen.next
      implicit val encoding = java.nio.charset.Charset.forName("UTF-8")
      val cmd = SubmitSm(seqNum, ServiceType.Default, from.`type`, from.npi, new COctetString(from.number),
                         to.`type`, to.npi, new COctetString(to.number), EsmClass(EsmClass.MessagingMode.Default, EsmClass.MessageType.NormalMessage),
                         0x34, Priority.Level0, NullTime, NullTime, RegisteredDelivery(), false, DataCodingScheme.SmscDefaultAlphabet,
                         0x0, body.length.toByte, new OctetString(body), Nil)
      log.info(s"Sending message $cmd")
      connection ! wire.Command(cmd)
      window = window.updated(seqNum, context.actorOf(SubmitSmRespWatcher.props(Set(seqNum), sender)))

    case SendEnquireLink =>
      log.debug("sending enquire link!")
      connection ! wire.Command(EnquireLink(sequenceNumberGen.next))

    case wire.Event(pdu @ SubmitSmResp(_, seqN, _)) if window.get(seqN).isDefined =>
      log.debug(s"Incoming SubmitSmResp $pdu")
      window(seqN) ! pdu
      window = window - seqN
    case wire.Event(pdu: SubmitSmResp) =>
      log.warning(s"SubmitSmResp for unknown sequence number: $pdu")
    case wire.Event(EnquireLink(seq)) =>
      connection ! wire.Command(EnquireLinkResp(seq))
    case wire.Event(msg: DeliverSm) =>
      log.info(s"Received message $msg")
      // XXX: decode actual message
      implicit val encoding = java.nio.charset.Charset.forName("UTF-8")
      val cmd = SmppClient.ReceiveMessage(msg.shortMessage.toString,
        to = Did(msg.destinationAddr.asString, msg.destAddrTon, msg.destAddrNpi),
        from = Did(msg.sourceAddr.asString, msg.sourceAddrTon, msg.sourceAddrNpi)
      )
      context.parent ! cmd
      connection ! wire.Command(DeliverSmResp(CommandStatus.ESME_ROK, msg.sequenceNumber, Some(COctetString.empty)))
    case wire.Event(msg: EnquireLinkResp) =>
    /*
    case wire.Event(msg: DataSm) =>
    case wire.Event(msg: AlertNotification) =>
    */
    case wire.Event(pdu: Pdu) =>
      log.warning(s"Received unsupported pdu: $pdu responding with GenericNack")
      connection ! wire.Command(GenericNack(CommandStatus.ESME_RCANCELFAIL, pdu.sequenceNumber))

  }
}
