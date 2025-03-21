package com.anymindgroup.pubsub

import com.anymindgroup.pubsub.OrderingKey
import com.anymindgroup.pubsub.PubsubTestSupport.topicWithSubscriptionGen
import com.anymindgroup.pubsub.http.HttpSubscriber

import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*
import zio.{NonEmptyChunk, RIO, Scope, Task, ZIO, durationInt}

object PubAndSubSpec {
  def spec(
    pkgName: String,
    // Publisher implementation to test against
    createPublisher: (PubsubConnectionConfig, TopicName, Encoding, Boolean) => RIO[Scope, Publisher[Any, String]],
    // Subscriber implementation to test against
    createSubscriber: PubsubConnectionConfig => RIO[Scope, Subscriber],
  ): Spec[Scope, Throwable] =
    suite(s"[$pkgName] Publisher/Subscriber spec")(
      suite("Published and consumed messages are matching")(
        (for {
          encoding       <- Encoding.values.toList
          enableOrdering <- List(true, false)
        } yield test(s"single message (encoding: $encoding, ordering enabled: $enableOrdering)") {
          for {
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
            connection   <- ZIO.service[PubsubConnectionConfig]
            publisher    <- createPublisher(connection, topic, encoding, enableOrdering)
            subscriber   <- createSubscriber(connection)
            message      <- publishMessageGen.runHead.map(_.get)
            publishedId  <- publisher.publish(message)
            consumed <-
              subscriber.subscribe(sub, Serde.utf8String).map(_._1).take(1).runCollect.map(_.head)
            _ <- assertTrue(consumed.messageId == publishedId)
            _ <- assertTrue(consumed.data == message.data)
            _ <- assertTrue(consumed.attributes == message.attributes)
            _ <- assertTrue(consumed.orderingKey == message.orderingKey)
          } yield assertCompletes
        }) ::: (for {
          encoding       <- Encoding.values.toList
          enableOrdering <- List(true, false)
        } yield test(s"multiple messages (encoding: $encoding, ordering enabled: $enableOrdering)") {
          for {
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
            connection   <- ZIO.service[PubsubConnectionConfig]
            publisher    <- createPublisher(connection, topic, encoding, enableOrdering)
            subscriber   <- createSubscriber(connection)
            messages     <- publishMessagesGen(1, 20).runHead.map(_.get)
            publishedIds <- publisher.publish(messages)
            _            <- assertTrue(publishedIds.length == messages.length)
            consumed     <- subscriber.subscribe(sub, Serde.utf8String).take(messages.length).runCollect
            _            <- assertTrue(consumed.length == publishedIds.length)
          } yield assertCompletes
        })
      ),
      test("Subscriber messages are acknowledged") {
        for {
          (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
          _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
          connection   <- ZIO.service[PubsubConnectionConfig]
          subscriber   <- createSubscriber(connection)
          messages     <- publishMessagesGen(1, 20).runHead.map(_.get)
          _            <- PubsubTestSupport.publishEvents(messages, topic)
          _ <- subscriber
                 .subscribe(sub, Serde.utf8String)
                 .take(messages.length.toLong)
                 .tap(_._2.ack()) // ack all messages
                 .runDrain
          unacked <- PubsubTestSupport.pull(sub, returnImmediately = false).timeout(2.seconds)
          _       <- assertTrue(unacked.forall(_.isEmpty))
        } yield assertCompletes
      },
      test("Subscriber messages are not-acknowledged") {
        for {
          (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
          _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
          connection   <- ZIO.service[PubsubConnectionConfig]
          subscriber   <- createSubscriber(connection)
          testMsgCount  = 2
          messages     <- publishMessagesGen(testMsgCount, testMsgCount).runHead.map(_.get)
          _            <- PubsubTestSupport.publishEvents(messages, topic)
          _ <- subscriber
                 .subscribe(sub, Serde.utf8String)
                 .tap(_._2.nack()) // nack all messages
                 .take(messages.length.toLong)
                 .runDrain
          // pull until all unacked messages are re-delivered as expected
          unacked <- ZStream
                       .unfoldZIO(0) { c =>
                         PubsubTestSupport
                           .pull(sub, maxMessages = messages.length, returnImmediately = false)
                           .map: chunk =>
                             if (c + chunk.length) >= messages.length then None
                             else Some((), c + chunk.length)
                       }
                       .runDrain
        } yield assertCompletes
      },
    ).provideSomeShared[Scope](
      PubsubTestSupport.emulatorBackendLayer(),
      PubsubTestSupport.testSubscriberLayer,
    ) @@ TestAspect.withLiveClock @@ TestAspect.nondeterministic @@ TestAspect.timeout(30.seconds)

  private val publishMessageGen = for {
    data        <- Gen.alphaNumericStringBounded(1, 500) // data can't be empty
    orderingKey <- Gen.option(Gen.alphaNumericString).map(_.flatMap(OrderingKey.fromString))
    attributes  <- Gen.mapOfBounded(0, 10)(Gen.alphaNumericString, Gen.alphaNumericString)
  } yield PublishMessage(
    data = data,
    orderingKey = orderingKey,
    attributes = attributes,
  )

  private def publishMessagesGen(min: Int, max: Int) =
    Gen.chunkOfBounded(min, max)(publishMessageGen).map(NonEmptyChunk.fromChunk(_).get)
}
