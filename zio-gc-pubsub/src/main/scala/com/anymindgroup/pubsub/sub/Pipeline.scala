package com.anymindgroup.pubsub.sub

import com.anymindgroup.pubsub.serde.Deserializer
import com.anymindgroup.pubsub.sub.*

import zio.stream.ZPipeline
import zio.{RIO, ZIO}

object Pipeline {

  private def decodedPipeline[R, E, B](
    f: DecodedReceipt[E] => RIO[R, B]
  ): ZPipeline[R, Throwable, DecodedReceipt[E], B] =
    ZPipeline.mapZIO[R, Throwable, DecodedReceipt[E], B](f)

  def processPipeline[R, E, T](
    process: ReceivedMessage[E] => RIO[R, T]
  ): ZPipeline[R, Throwable, DecodedReceipt[E], T] =
    decodedPipeline[R, E, T] { case (event, ackReply) =>
      process(event)
        .tapErrorCause(c => ZIO.logErrorCause("Error on processing event", c))
        .tap(_ => ackReply.ack())
    }

  def autoAckPipeline[E]: TaskPipeline[Any, (E, AckReply), E] =
    ZPipeline.mapZIO[Any, Throwable, (E, AckReply), E] { case (event, ackReply) =>
      ackReply.ack().as(event)
    }

  def deserializerPipeline[R, T](deserializer: Deserializer[R, T]): DecodedRPipeline[R, T] =
    ZPipeline.mapZIO { case (receivedMessage, ackReply) =>
      deserializer
        .deserialize(receivedMessage)
        .map(r => (ReceivedMessage(receivedMessage.meta, r), ackReply))
    }
}
