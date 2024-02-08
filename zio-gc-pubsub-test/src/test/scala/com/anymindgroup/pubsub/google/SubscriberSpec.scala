package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.google.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.google.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.anymindgroup.pubsub.sub.{AckId, DeadLettersSettings, Subscription}
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{PubsubMessage, SubscriptionName, ReceivedMessage as GReceivedMessage}
import vulcan.Codec
import zio.test.*
import zio.test.Assertion.*
import zio.{Duration, RIO, Scope, ZIO}

import java.time.Instant
import scala.jdk.CollectionConverters.*

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
        (_, testResultA) <- ZIO.scoped {
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
    test("Subscription with dead letters policy shouldn't be created without a dead letter topic") {
      for {
        (connection, topicName) <- initTopicWithSchema
        tempSubName             <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _).runHead.map(_.get)
        subAdminClient          <- SubscriptionAdmin.makeClient(connection)
        deadLettersSettings      = DeadLettersSettings("non-existing-topic", 5)
        subscription = Subscription(
                         topicName = topicName,
                         name = tempSubName,
                         filter = None,
                         enableOrdering = true,
                         expiration = None,
                         deadLettersSettings = Some(deadLettersSettings),
                       )
        subscriptionCreateAttempt <-
          SubscriptionAdmin
            .createSubscriptionIfNotExists(connection, subAdminClient, subscription)
            .exit
      } yield assert(subscriptionCreateAttempt)(
        fails(
          isSubtype[NotFoundException](
            hasField(
              "description",
              e => Option(e.getMessage).getOrElse(""),
              equalTo(
                "io.grpc.StatusRuntimeException: NOT_FOUND: Dead letter topic not found"
              ),
            )
          )
        )
      )
    }.provideSome[Scope](
      emulatorConnectionConfigLayer()
    ),
    test("Subscription with dead letters policy should be successfully created with dead letter topic") {
      for {
        (connection, topicName) <- initTopicWithSchemaAndDeadLetters
        tempSubName             <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _).runHead.map(_.get)
        subAdminClient          <- SubscriptionAdmin.makeClient(connection)
        deadLettersSettings      = DeadLettersSettings(s"${topicName}__dead_letters", 5)
        subscription = Subscription(
                         topicName = topicName,
                         name = tempSubName,
                         filter = None,
                         enableOrdering = true,
                         expiration = None,
                         deadLettersSettings = Some(deadLettersSettings),
                       )
        _ <-
          SubscriptionAdmin
            .createSubscriptionIfNotExists(connection, subAdminClient, subscription)
      } yield assertCompletes
    }.provideSome[Scope](
      emulatorConnectionConfigLayer()
    ),
  )

  private def subscriptionExists(subscriptionName: String): RIO[GcpProject & SubscriptionAdminClient, Boolean] = for {
    client         <- ZIO.service[SubscriptionAdminClient]
    subscriptionId <- ZIO.serviceWith[GcpProject](g => SubscriptionName.of(g.name, subscriptionName))
    result <- ZIO.attempt(client.getSubscription(subscriptionId)).as(true).catchSome { case _: NotFoundException =>
                ZIO.succeed(false)
              }
  } yield result

  def createRandomTopic: RIO[PubsubConnectionConfig.Emulator & Scope, Topic[Any, Int]] =
    topicNameGen.runHead
      .map(_.get)
      .map(_.getTopic())
      .map(topicName =>
        Topic(
          topicName,
          SchemaSettings(
            schema = None,
            encoding = testEncoding,
          ),
          VulcanSerde.fromAvroCodec(Codec.int, testEncoding),
        )
      )

  def createRandomTopicWithDeadLettersTopic: RIO[PubsubConnectionConfig.Emulator & Scope, List[Topic[Any, Int]]] =
    createRandomTopic.map(topic =>
      List(
        topic,
        topic.copy(name = s"${topic.name}__dead_letters"),
      )
    )

  private def initTopicWithSchema
    : RIO[PubsubConnectionConfig.Emulator & Scope, (PubsubConnectionConfig.Emulator, String)] =
    createRandomTopic.flatMap(t => initTopicsWithSchema(List(t)))

  private def initTopicWithSchemaAndDeadLetters
    : RIO[PubsubConnectionConfig.Emulator & Scope, (PubsubConnectionConfig.Emulator, String)] =
    createRandomTopicWithDeadLettersTopic.flatMap(initTopicsWithSchema)

  private def initTopicsWithSchema(
    topics: List[Topic[Any, Int]]
  ): RIO[PubsubConnectionConfig.Emulator & Scope, (PubsubConnectionConfig.Emulator, String)] = for {
    connection <- ZIO.service[PubsubConnectionConfig.Emulator]
    // init topic with schema settings
    _ <- PubsubAdmin.setup(connection, topics, Nil)
  } yield (connection, topics.head.name)
}
