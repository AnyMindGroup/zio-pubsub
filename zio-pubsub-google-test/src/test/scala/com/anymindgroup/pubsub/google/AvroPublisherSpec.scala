package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.*
import com.anymindgroup.pubsub.PubsubTestSupport.*
import com.anymindgroup.pubsub.google.TestSupport.*
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.pubsub.v1.{SubscriptionName, TopicName}

import zio.test.Assertion.{equalTo, hasSameElements}
import zio.test.{Spec, ZIOSpecDefault, *}
import zio.{RIO, Ref, Scope, ZIO, durationInt}

object AvroPublisherSpec extends ZIOSpecDefault {
  final case class TestConfig(
    connection: PubsubConnectionConfig,
    publisherConf: PublisherConfig,
    subscription: Subscription,
    topic: Topic[Any, TestEvent],
  ) {
    val subscriptionId: SubscriptionName =
      SubscriptionName.of(subscription.name.projectId, subscription.name.subscription)
    val topicId: TopicName = publisherConf.topicId
  }

  val testPublishMessageGen: Gen[Any, PublishMessage[TestEvent]] = for {
    testEvent   <- testEventGen
    attrs       <- Gen.mapOfBounded(0, 10)(Gen.alphaNumericString, Gen.alphaNumericString)
    orderingKey <- Gen.alphaNumericString.map(OrderingKey.fromString(_))
  } yield PublishMessage(testEvent, orderingKey, attrs)

  def randomTestConfig(encoding: Encoding): RIO[SubscriptionAdminClient & PubsubConnectionConfig.Emulator, TestConfig] =
    for {
      (topicName, subscriptionName) <- someTopicWithSubscriptionName("any")
      conn                          <- ZIO.service[PubsubConnectionConfig.Emulator]
      schema =
        SchemaSettings(
          schema = Some(
            SchemaRegistry(
              name = SchemaName(projectId = topicName.projectId, schemaId = s"${topicName.topic}_v1"),
              schemaType = SchemaType.Avro,
              definition = ZIO.succeed(TestEvent.avroCodecSchema),
            )
          ),
          encoding = encoding,
        )
      topic = Topic[Any, TestEvent](
                topicName,
                schema,
                VulcanSerde.fromAvroCodec(TestEvent.avroCodec, encoding),
              )
      publisherConfig = PublisherConfig(topicName = topicName, encoding = encoding, enableOrdering = true)
      subscription = Subscription(
                       topicName = topic.name,
                       name = subscriptionName,
                       filter = None,
                       enableOrdering = true,
                       expiration = None,
                       deadLettersSettings = None,
                     )
    } yield TestConfig(conn, publisherConfig, subscription, topic)

  override def spec: Spec[Scope, Any] = suite("AvroPublisherSpec")(
    (test("publish with custom attributes and ordering keys") {
      for {
        testConf     <- randomTestConfig(Encoding.Binary)
        _            <- PubsubAdmin.setup(List(testConf.topic), List(testConf.subscription), testConf.connection)
        testMessages <- testPublishMessageGen.runCollectN(50).map(_.toVector)
        p <- google.Publisher.make[Any, TestEvent](
               testConf.publisherConf,
               VulcanSerde.fromAvroCodec(TestEvent.avroCodec, Encoding.Binary),
               testConf.connection,
             )
        consumedRef <- Ref.make(Vector.empty[ReceivedMessage.Raw])
        rawStream <- google.Subscriber.makeRawStreamingPullSubscription(
                       connection = testConf.connection,
                       subscriptionName = testConf.subscription.name,
                       streamAckDeadlineSeconds = google.Subscriber.defaultStreamAckDeadlineSeconds,
                       retrySchedule = google.Subscriber.defaultRetrySchedule,
                     )
        _             <- rawStream.map(_._1).mapZIO(e => consumedRef.getAndUpdate(_ :+ e)).runDrain.forkScoped
        _             <- ZIO.foreachDiscard(testMessages)(p.publish) *> ZIO.sleep(200.millis)
        consumed      <- consumedRef.get
        publishedAttrs = testMessages.map(_.attributes)
        consumedAttr =
          consumed
            .map(
              // filter out attributes added by google
              _.meta.attributes.filterNot(_._1.startsWith("googclient_"))
            )
        publishedOrderingKeys = testMessages.map(_.orderingKey)
        consumedOrderingKeys  = consumed.map(_.meta.orderingKey)
        _                    <- assert(publishedOrderingKeys)(hasSameElements(consumedOrderingKeys))
        _                    <- assert(consumedAttr)(hasSameElements(publishedAttrs))
      } yield assert(publishedOrderingKeys)(hasSameElements(consumedOrderingKeys))
    }) ::
      List(
        Encoding.Json,
        Encoding.Binary,
      ).map { encoding =>
        test(s"publish and consume with $encoding encoding") {
          for {
            testConf       <- randomTestConfig(encoding)
            _              <- PubsubAdmin.setup(List(testConf.topic), List(testConf.subscription), testConf.connection)
            testEventsData <- testEventGen.runCollectN(10)
            p <- google.Publisher.make[Any, TestEvent](
                   testConf.publisherConf,
                   VulcanSerde.fromAvroCodec(TestEvent.avroCodec, encoding),
                   testConf.connection,
                 )
            consumedRef <- Ref.make(Vector.empty[TestEvent])
            stream <-
              google.Subscriber
                .makeStreamingPullSubscriber(
                  testConf.connection
                )
                .map(
                  _.subscribe(testConf.subscription.name, VulcanSerde.fromAvroCodec(TestEvent.avroCodec, encoding))
                )
            _         <- stream.via(Pipeline.processPipeline(e => consumedRef.getAndUpdate(_ :+ e.data))).runDrain.forkScoped
            testEvents = testEventsData.map(d => PublishMessage[TestEvent](d, None, Map.empty[String, String]))
            _         <- ZIO.foreachDiscard(testEvents)(e => p.publish(e))
            consumed  <- consumedRef.get.repeatUntil(_.length == testEventsData.length).timeout(5.seconds)
          } yield assert(consumed)(equalTo(Some(testEventsData.toVector)))
        }
      }
  ).provideSomeShared[Scope](
    emulatorConnectionConfigLayer() >+> SubscriptionAdmin.layer >+> TopicAdmin.layer
  ) @@ TestAspect.withLiveClock @@ TestAspect.nondeterministic @@ TestAspect.timeout(60.seconds)

}
