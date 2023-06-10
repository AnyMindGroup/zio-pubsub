package com.anymindgroup.pubsub.sub

import java.time.Instant

import com.anymindgroup.pubsub.model.{MessageId, OrderingKey}

final case class ReceivedMessage[T](meta: ReceivedMessage.Metadata, data: T)

object ReceivedMessage {
  type Raw = ReceivedMessage[Array[Byte]]

  final case class Metadata(
    messageId: MessageId,
    ackId: AckId,
    publishTime: Instant,
    orderingKey: Option[OrderingKey],
    attributes: Map[String, String],
    deliveryAttempt: Int,
  )

}
