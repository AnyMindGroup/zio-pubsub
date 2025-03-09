package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.{TopicName, *}
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.anymindgroup.pubsub.sub.{AckId, DeadLettersSettings, SubscriberFilter, Subscription}
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{
  PubsubMessage,
  ReceivedMessage as GReceivedMessage,
  SubscriptionName as GSubscriptionName,
  TopicName as GTopicName,
}
import vulcan.Codec

import zio.test.*
import zio.test.Assertion.*
import zio.{Duration, RIO, Scope, Task, ZIO}

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
    .setMessage {
      val msgBuilder = PubsubMessage
        .newBuilder()
        .putAllAttributes(attrs.asJava)
        .setPublishTime(Timestamp.newBuilder.setSeconds(ts.getEpochSecond()).setNanos(ts.getNano()).build())
        .setMessageId(messageId)

      orderingKey.fold(msgBuilder)(k => msgBuilder.setOrderingKey(k)).build()
    }
    .setAckId(ackId)
    .setDeliveryAttempt(deliveryAttempt)
    .build()

  private def deleteSubscription(
    projectName: String,
    subscriptionName: String,
    subscriptionAdmin: SubscriptionAdminClient,
  ): Task[Unit] =
    ZIO.attempt {
      subscriptionAdmin.deleteSubscription(GSubscriptionName.of(projectName, subscriptionName))
    }.ignore

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberSpec")(
    test("create a subscription and remove after usage") {
      for {
        connection <- ZIO.service[PubsubConnectionConfig.Emulator]
        topicName  <- initTopicWithSchema(connection)
        tempSubName <- Gen
                         .alphaNumericStringBounded(10, 10)
                         .map("sub_" + _)
                         .runHead
                         .map(_.get)
                         .map: s =>
                           SubscriptionName("any", s)
        client <- SubscriptionAdmin.makeClient(connection)
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
                                existsOnCreation <- subscriptionExists(tempSubName, client)
                              } yield (subscription, assertTrue(existsOnCreation))
                            }
        existsAfterUsage <- subscriptionExists(tempSubName, client)
      } yield testResultA && assertTrue(!existsAfterUsage)
    } @@ TestAspect.nondeterministic,
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
    test("Dead letters topic should be created if subscription has dead letters policy") {
      for {
        connection          <- ZIO.service[PubsubConnectionConfig.Emulator]
        topicName           <- initTopicWithSchema(connection)
        tempSubName         <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _).runHead.map(_.get)
        deadLetterTopicName <- Live.live(Gen.alphaNumericStringBounded(10, 10).map("dlt_" + _).runHead.map(_.get))
        topicAdmin          <- TopicAdmin.makeClient(connection)
        deadLettersSettings  = DeadLettersSettings(TopicName(topicName.projectId, deadLetterTopicName), 5)
        dltNotExists <-
          ZIO.succeed(topicAdmin.getTopic(GTopicName.format(topicName.projectId, deadLetterTopicName))).exit
        _ <-
          assertTrue(dltNotExists.isFailure).label("Dead letter topic should not exist before creating a subscription")
        subscription = Subscription(
                         topicName = topicName,
                         name = SubscriptionName(topicName.projectId, tempSubName),
                         filter = None,
                         enableOrdering = true,
                         expiration = None,
                         deadLettersSettings = Some(deadLettersSettings),
                       )
        _ <- SubscriptionAdmin.createOrUpdate(subscription = subscription, connection = connection).exit
        dltExists <-
          ZIO.succeed(topicAdmin.getTopic(GTopicName.format(topicName.projectId, deadLetterTopicName))).exit
        _ <-
          assertTrue(dltExists.isSuccess).label("Dead letter topic should exist after creating a subscription")
      } yield assertCompletes
    },
    test("Subscription with dead letters policy should be successfully created with dead letter topic") {
      for {
        connection         <- ZIO.service[PubsubConnectionConfig.Emulator]
        topicName          <- initTopicWithSchemaAndDeadLetters(connection)
        tempSubName        <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _).runHead.map(_.get)
        deadLettersSettings = DeadLettersSettings(topicName.copy(topic = s"${topicName.topic}__dead_letters"), 5)
        subscription = Subscription(
                         topicName = topicName,
                         name = SubscriptionName(topicName.projectId, tempSubName),
                         filter = None,
                         enableOrdering = true,
                         expiration = None,
                         deadLettersSettings = Some(deadLettersSettings),
                       )
        _ <- SubscriptionAdmin.createOrUpdate(connection = connection, subscription = subscription)
      } yield assertCompletes
    },
    test("Subscription without dead letters policy should be updated when already exist") {
      for {
        connection <- ZIO.service[PubsubConnectionConfig.Emulator]
        topicName  <- initTopicWithSchemaAndDeadLetters(connection)
        client     <- SubscriptionAdmin.makeClient(connection)
        tempSubName <- Gen
                         .alphaNumericStringBounded(10, 10)
                         .map("sub_" + _)
                         .runHead
                         .map(_.get)
                         .map: s =>
                           SubscriptionName(topicName.projectId, s)
        _                  <- deleteSubscription(topicName.projectId, tempSubName.subscription, client)
        deadLettersSettings = DeadLettersSettings(topicName.copy(topic = s"${topicName.topic}__dead_letters"), 5)
        subscription = Subscription(
                         topicName = topicName,
                         name = tempSubName,
                         filter = Some(SubscriberFilter.matchingAttributes(Map("name" -> "20"))),
                         enableOrdering = true,
                         expiration = None,
                         deadLettersSettings = None,
                       )

        _ <- SubscriptionAdmin.createOrUpdate(connection = connection, subscription = subscription)
        existingSub <-
          SubscriptionAdmin.fetchCurrentSubscription(client, tempSubName)
        _                         <- assertTrue(existingSub.is(_.some).deadLettersSettings.isEmpty)
        subscriptionWithDeadLetter = subscription.copy(deadLettersSettings = Some(deadLettersSettings))
        _                         <- SubscriptionAdmin.createOrUpdate(connection = connection, subscription = subscriptionWithDeadLetter)
        afterUpdate               <- SubscriptionAdmin.fetchCurrentSubscription(client, tempSubName)
        _                         <- assertTrue(afterUpdate.is(_.some).deadLettersSettings.get == deadLettersSettings)
        _                         <- SubscriptionAdmin.createOrUpdate(connection = connection, subscription = subscription)
        afterUpdateToEmpty        <- SubscriptionAdmin.fetchCurrentSubscription(client, tempSubName)
        _                         <- assertTrue(afterUpdateToEmpty.is(_.some).deadLettersSettings.isEmpty)
      } yield assertCompletes
    },
    test("Fetch Not Found Subscription should be handled properly") {
      for {
        connection <- ZIO.service[PubsubConnectionConfig.Emulator]
        client     <- SubscriptionAdmin.makeClient(connection)
        tempSubName <- Gen
                         .alphaNumericStringBounded(10, 10)
                         .map("sub_" + _)
                         .runHead
                         .map(_.get)
                         .map: s =>
                           SubscriptionName("any", s)
        _      <- deleteSubscription(tempSubName.projectId, tempSubName.subscription, client)
        result <- SubscriptionAdmin.fetchCurrentSubscription(client, tempSubName).either
        _      <- assertTrue(result.is(_.right).isEmpty)
      } yield assertCompletes
    } @@ TestAspect.nondeterministic,
  ).provideSomeShared[Scope](emulatorConnectionConfigLayer())

  private def subscriptionExists(subscriptionName: SubscriptionName, client: SubscriptionAdminClient): Task[Boolean] =
    ZIO
      .attempt(
        client.getSubscription(GSubscriptionName.of(subscriptionName.projectId, subscriptionName.subscription))
      )
      .as(true)
      .catchSome { case _: NotFoundException =>
        ZIO.succeed(false)
      }

  def createRandomTopic: ZIO[Any, Nothing, Topic[Any, Int]] =
    topicNameGen("any").runHead
      .map(_.get)
      .map(topicName =>
        Topic[Any, Int](
          topicName,
          SchemaSettings(
            schema = None,
            encoding = testEncoding,
          ),
          VulcanSerde.fromAvroCodec(Codec.int, testEncoding),
        )
      )

  def createRandomTopicWithDeadLettersTopic: ZIO[Any, Nothing, List[Topic[Any, Int]]] =
    createRandomTopic.map(topic =>
      List(
        topic,
        topic.copy(name = topic.name.copy(topic = s"${topic.name.topic}__dead_letters")),
      )
    )

  private def initTopicWithSchema(connection: PubsubConnectionConfig.Emulator) =
    createRandomTopic.flatMap(t => initTopicsWithSchema(List(t), connection))

  private def initTopicWithSchemaAndDeadLetters(connection: PubsubConnectionConfig.Emulator): RIO[Scope, TopicName] =
    createRandomTopicWithDeadLettersTopic.flatMap(initTopicsWithSchema(_, connection))

  private def initTopicsWithSchema(
    topics: List[Topic[Any, Int]],
    connection: PubsubConnectionConfig.Emulator,
  ) = PubsubAdmin.setup(connection = connection, topics = topics, subscriptions = Nil).as(topics.head.name)
}
