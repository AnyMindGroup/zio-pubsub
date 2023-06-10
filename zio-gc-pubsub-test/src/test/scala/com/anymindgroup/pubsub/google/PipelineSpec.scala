package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.model.{MessageId, OrderingKey}
import com.anymindgroup.pubsub.sub.AckId
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{PubsubMessage, ReceivedMessage}

import zio.test.Assertion.*
import zio.test.{Spec, ZIOSpecDefault, *}

object PipelineSpec extends ZIOSpecDefault {

  val receivedMessageGen: Gen[Any, ReceivedMessage] = for {
    ackId           <- Gen.uuid.map(_.toString())
    messageId       <- Gen.alphaNumericStringBounded(1, 20)
    deliveryAttempt <- Gen.int(0, Int.MaxValue)
    orderingKey     <- Gen.option(Gen.alphaNumericString)
    attrs           <- Gen.mapOfBounded(0, 20)(Gen.alphaNumericString, Gen.alphaNumericString)
    ts              <- Gen.long(0L, Int.MaxValue.toLong).map(Instant.ofEpochSecond)
  } yield ReceivedMessage
    .newBuilder()
    .setMessage({
      val msgBuilder = PubsubMessage
        .newBuilder()
        .putAllAttributes(attrs.asJava)
        .setPublishTime(Timestamp.newBuilder.setSeconds(ts.getEpochSecond()).setNanos(ts.getNano()).build())
        .setMessageId(messageId)

      orderingKey.fold(msgBuilder)(k => msgBuilder.setOrderingKey(k)).build()
    })
    .setAckId(ackId)
    .setDeliveryAttempt(deliveryAttempt)
    .build()

  override def spec: Spec[Any, Any] = suite("PipelineSpec")(
    test("get meta data from ReceivedMessage") {
      check(receivedMessageGen) { sample =>
        val res = Pipeline.metadataOfMessage(sample)
        for {
          _ <- assertTrue(res.ackId == AckId(sample.getAckId()))
          _ <- assertTrue(res.messageId == MessageId(sample.getMessage.getMessageId()))
          _ <- assertTrue(
                 res.orderingKey == OrderingKey.fromString(sample.getMessage.getOrderingKey())
               )
          _ <- assert(res.attributes)(hasSameElements(sample.getMessage.getAttributesMap().asScala))
          _ <- assertTrue(res.publishTime.getEpochSecond == sample.getMessage().getPublishTime().getSeconds())
          _ <- assertTrue(res.publishTime.getNano == sample.getMessage().getPublishTime().getNanos())
          _ <- assertTrue(res.deliveryAttempt == sample.getDeliveryAttempt())
        } yield assertCompletes
      }
    }
  )
}
