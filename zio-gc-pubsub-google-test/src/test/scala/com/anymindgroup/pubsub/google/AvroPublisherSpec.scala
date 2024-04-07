package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.PubsubTestSupport.*
import com.anymindgroup.pubsub.google.TestSupport.*
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.pub.*
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.anymindgroup.pubsub.sub.*
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
    val subscriptionId: SubscriptionName = SubscriptionName.of(connection.project.name, subscription.name)
    val topicId: TopicName               = publisherConf.topicId
  }

  val testPublishMessageGen: Gen[Any, PublishMessage[TestEvent]] = for {
    testEvent   <- testEventGen
    attrs       <- Gen.mapOfBounded(0, 10)(Gen.alphaNumericString, Gen.alphaNumericString)
    orderingKey <- Gen.alphaNumericString.map(OrderingKey.fromString(_))
  } yield PublishMessage(testEvent, orderingKey, attrs)

  def randomTestConfig(encoding: Encoding): RIO[SubscriptionAdminClient & PubsubConnectionConfig.Emulator, TestConfig] =
    for {
      (topicName, subscriptionName) <- someTopicWithSubscriptionName
      conn                          <- ZIO.service[PubsubConnectionConfig.Emulator]
      schema =
        SchemaSettings(
          schema = Some(
            SchemaRegistry(
              id = s"${topicName.getTopic()}_v1",
              schemaType = SchemaType.Avro,
              definition = ZIO.succeed(TestEvent.avroCodecSchema),
            )
          ),
          encoding = encoding,
        )
      topic = Topic[Any, TestEvent](
                topicName.getTopic(),
                schema,
                VulcanSerde.fromAvroCodec(TestEvent.avroCodec, encoding),
              )
      publisherConfig = PublisherConfig.forTopic(conn, topic, enableOrdering = true)
      subscription = Subscription(
                       topicName = topic.name,
                       name = subscriptionName.getSubscription(),
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
        _            <- PubsubAdmin.setup(List(testConf.topic), List(testConf.subscription))
        testMessages <- testPublishMessageGen.runCollectN(50).map(_.toVector)
        p <- google.Publisher.make[Any, TestEvent](
               testConf.publisherConf,
               VulcanSerde.fromAvroCodec(TestEvent.avroCodec, Encoding.Binary),
             )
        consumedRef <- Ref.make(Vector.empty[ReceivedMessage.Raw])
        rawStream <- google.Subscriber.makeRawStreamingPullSubscription(
                       testConf.connection,
                       testConf.subscription.name,
                       google.Subscriber.defaultStreamAckDeadlineSeconds,
                       google.Subscriber.defaultRetrySchedule,
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
      } yield assert(consumedAttr)(hasSameElements(publishedAttrs))
    }) ::
      List(
        Encoding.Json,
        Encoding.Binary,
      ).map { encoding =>
        test(s"publish and consume with $encoding encoding") {
          for {
            testConf       <- randomTestConfig(encoding)
            _              <- PubsubAdmin.setup(List(testConf.topic), List(testConf.subscription))
            testEventsData <- testEventGen.runCollectN(10)
            p <- google.Publisher.make[Any, TestEvent](
                   testConf.publisherConf,
                   VulcanSerde.fromAvroCodec(TestEvent.avroCodec, encoding),
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
            _         <- ZIO.foreachDiscard(testEvents)(e => p.publish(e)) *> ZIO.sleep(200.millis)
            consumed  <- consumedRef.get
          } yield assert(consumed)(equalTo(testEventsData.toVector))
        }
      }
  ).provideSomeShared[Scope](
    emulatorConnectionConfigLayer() >+> SubscriptionAdmin.layer >+> TopicAdmin.layer
  ) @@ TestAspect.withLiveClock @@ TestAspect.nondeterministic @@ TestAspect.timeout(60.seconds)

}
