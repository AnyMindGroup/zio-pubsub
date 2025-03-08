package com.anymindgroup.pubsub.http

import scala.util.Random

import com.anymindgroup.gcp.auth.TokenProvider
import com.anymindgroup.http.httpBackendScoped
import com.anymindgroup.pubsub.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.{SubscriptionName, TopicName}
import com.anymindgroup.pubsub.pub.PublishMessage
import com.anymindgroup.pubsub.{Publisher, PubsubConnectionConfig, Serde}
import sttp.client4.Backend

import zio.test.*
import zio.test.Assertion.*
import zio.{Chunk, Task, ZIO, ZLayer}

object PubAndSubSpec extends ZIOSpecDefault {
  val connection: PubsubConnectionConfig.Emulator = PubsubConnectionConfig.Emulator("localhost", 8085)
  val testTopic                                   = TopicName("any", s"topic_${Random.alphanumeric.take(10).mkString}")
  val testSub                                     = SubscriptionName("any", s"sub_${Random.alphanumeric.take(10).mkString}")

  val pubLayer: ZLayer[Any, Throwable, Publisher[Any, String]] = ZLayer.scoped {
    for {
      backend <- httpBackendScoped()
      publisher = HttpPublisher.make[Any, String](
                    connection = connection,
                    topicName = testTopic,
                    serializer = Serde.utf8String,
                    backend = backend,
                    tokenProvider = TokenProvider.noTokenProvider,
                  )
    } yield publisher
  }

  val subLayer: ZLayer[Any, Throwable, HttpSubscriber] = ZLayer.scoped {
    for {
      backend <- httpBackendScoped()
      subscriber <- HttpSubscriber.make(
                      connection = connection,
                      backend = backend,
                      tokenProvider = TokenProvider.noTokenProvider,
                    )
    } yield subscriber
  }

  override def spec: Spec[Any, Any] = suite("PubAndSubSpec")(
    test("Publish and consume") {
      for {
        _      <- createTopicWithSubscription(testTopic, testSub).provide(emulatorBackendLayer())
        p      <- ZIO.service[Publisher[Any, String]]
        s      <- ZIO.service[HttpSubscriber]
        samples = Chunk.fromIterable((1 to 200).map(i => s"message $i"))
        _      <- ZIO.foreachParDiscard(samples)(sample => p.publish(PublishMessage(sample, None, Map.empty)))
        collected <- s.subscribe(testSub, Serde.utf8String)
                       .take(samples.length.toLong)
                       .mapZIO { case (m, ack) => ack.ack().as(m) }
                       .runCollect
        _ <- assert(samples)(hasSameElements(collected.map(_.data)))
        _ <- assertZIO(s.pull(testSub, Some(true)))(isEmpty) // all messages were acknowlegded
      } yield assertCompletes
    }
  ).provide(pubLayer ++ subLayer)
}
