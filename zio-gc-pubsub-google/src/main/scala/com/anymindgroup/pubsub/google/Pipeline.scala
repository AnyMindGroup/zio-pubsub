package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.Deserializer
import com.anymindgroup.pubsub.sub.*
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.stream.ZPipeline
import zio.{RIO, ZIO}

object Pipeline {

  private def decodedPipeline[R, E, B](f: DecodedRecord[E] => RIO[R, B]): ZPipeline[R, Throwable, DecodedRecord[E], B] =
    ZPipeline.mapZIO[R, Throwable, DecodedRecord[E], B](f)

  def processPipeline[R, E, T](process: E => RIO[R, T]): ZPipeline[R, Throwable, DecodedRecord[E], T] =
    decodedPipeline[R, E, T] { case (event, ackReply) =>
      process(event)
        .tapErrorCause(c => ZIO.logErrorCause("Error on processing event", c))
        .tap(_ => ackReply.ack())
    }

  private[pubsub] def autoAckRawPipeline[E]: TaskPipeline[Any, RawRecord, GReceivedMessage] =
    ZPipeline.mapZIO[Any, Throwable, RawRecord, GReceivedMessage] { case (event, ackReply) => ackReply.ack().as(event) }

  def autoAckPipeline[E]: TaskPipeline[Any, DecodedRecord[E], E] = decodedPipeline[Any, E, E] {
    case (event, ackReply) =>
      ackReply.ack().as(event)
  }

  def deserializerPipeline[R, T](deserializer: Deserializer[R, T]): DecodedRPipeline[R, ReceivedMessage[T]] =
    ZPipeline.mapZIO { case (receivedMessage, ackReply) =>
      val meta = metadataOfMessage(receivedMessage)

      deserializer
        .deserialize(
          ReceivedMessage(
            meta = meta,
            data = receivedMessage.getMessage.getData.toByteArray,
          )
        )
        .map(data => (ReceivedMessage(meta, data), ackReply))
    }

  private[pubsub] def metadataOfMessage(rm: GReceivedMessage): ReceivedMessage.Metadata = {
    val msg = rm.getMessage
    val ts  = msg.getPublishTime()

    ReceivedMessage.Metadata(
      messageId = MessageId(msg.getMessageId()),
      ackId = AckId(rm.getAckId()),
      orderingKey = OrderingKey.fromString(msg.getOrderingKey()),
      publishTime = Instant
        .ofEpochSecond(ts.getSeconds())
        .plusNanos(ts.getNanos().toLong),
      attributes = msg.getAttributesMap.asScala.toMap,
      deliveryAttempt = rm.getDeliveryAttempt(),
    )
  }
}
