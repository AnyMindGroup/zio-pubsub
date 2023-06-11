package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.google.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.google.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.anymindgroup.pubsub.sub.AckId
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{PubsubMessage, ReceivedMessage as GReceivedMessage, SubscriptionName}
import vulcan.Codec

import zio.test.Assertion.*
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, *}
import zio.{Duration, RIO, Scope, ZIO}

object SubscriberSpec extends ZIOSpecDefault {

  private val testEncoding   = Encoding.Binary
  private val enableOrdering = false

  val receivedMessageGen: Gen[Any, GReceivedMessage] = for {
    ackId           <- Gen.uuid.map(_.toString())
    messageId       <- Gen.alphaNumericStringBounded(1, 20)
    deliveryAttempt <- Gen.int(0, Int.MaxValue)
    orderingKey     <- Gen.option(Gen.alphaNumericString)
    attrs           <- Gen.mapOfBounded(0, 20)(Gen.alphaNumericString, Gen.alphaNumericString)
    ts              <- Gen.long(0L, Int.MaxValue.toLong).map(Instant.ofEpochSecond)
  } yield GReceivedMessage
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

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberSpec")(
    test("create a subscription and remove after usage") {
      for {
        (connection, topicName) <- initTopicWithSchema
        tempSubName             <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _).runHead.map(_.get)
        (subscription, testResultA) <- ZIO.scoped {
                                         for {
                                           subscription <- Subscriber
                                                             .makeTempRawStreamingPullSubscription(
                                                               connection = connection,
                                                               topicName = topicName,
                                                               subscriptionName = tempSubName,
                                                               subscriptionFilter = None,
                                                               maxTtl = Duration.Infinity,
                                                               enableOrdering = enableOrdering,
                                                             )
                                           existsOnCreation <- subscriptionExists(tempSubName)
                                         } yield (subscription, assertTrue(existsOnCreation))
                                       }
        existsAfterUsage <- subscriptionExists(tempSubName)
      } yield testResultA && assertTrue(!existsAfterUsage)
    }.provideSome[Scope](
      emulatorConnectionConfigLayer(),
      SubscriptionAdmin.layer,
    ) @@ TestAspect.nondeterministic,
    test("get meta data from ReceivedMessage") {
      check(receivedMessageGen) { sample =>
        val res = Subscriber.toRawReceivedMessage(sample)
        for {
          _ <- assertTrue(res.meta.ackId == AckId(sample.getAckId()))
          _ <- assertTrue(res.meta.messageId == MessageId(sample.getMessage.getMessageId()))
          _ <- assertTrue(
                 res.meta.orderingKey == OrderingKey.fromString(sample.getMessage.getOrderingKey())
               )
          _ <- assert(res.meta.attributes)(hasSameElements(sample.getMessage.getAttributesMap().asScala))
          _ <- assertTrue(res.meta.publishTime.getEpochSecond == sample.getMessage().getPublishTime().getSeconds())
          _ <- assertTrue(res.meta.publishTime.getNano == sample.getMessage().getPublishTime().getNanos())
          _ <- assertTrue(res.meta.deliveryAttempt == sample.getDeliveryAttempt())
          _ <- assertTrue(res.data.length == sample.getMessage().getData().size())
        } yield assertCompletes
      }
    },
  )

  private def subscriptionExists(subscriptionName: String): RIO[GcpProject & SubscriptionAdminClient, Boolean] = for {
    client         <- ZIO.service[SubscriptionAdminClient]
    subscriptionId <- ZIO.serviceWith[GcpProject](g => SubscriptionName.of(g.name, subscriptionName))
    result <- ZIO.attempt(client.getSubscription(subscriptionId)).as(true).catchSome { case _: NotFoundException =>
                ZIO.succeed(false)
              }
  } yield result

  private def initTopicWithSchema
    : RIO[PubsubConnectionConfig.Emulator & Scope, (PubsubConnectionConfig.Emulator, String)] = for {
    connection <- ZIO.service[PubsubConnectionConfig.Emulator]
    topicName  <- topicNameGen.runHead.map(_.get).map(_.getTopic())
    topic = Topic(
              topicName,
              SchemaSettings(
                schema = None,
                encoding = testEncoding,
              ),
              VulcanSerde.fromAvroCodec(Codec.int, testEncoding),
            )
    // init topic with schema settings
    _ <- PubsubAdmin.setup(connection, List(topic), Nil)
  } yield (connection, topicName)
}
