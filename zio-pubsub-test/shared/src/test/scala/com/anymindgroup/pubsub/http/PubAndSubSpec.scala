package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.TokenProvider
import com.anymindgroup.http.HttpClientBackendPlatformSpecific
import com.anymindgroup.pubsub.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.model.{SubscriptionName, TopicName}
import com.anymindgroup.pubsub.pub.PublishMessage
import com.anymindgroup.pubsub.sub.Subscriber
import com.anymindgroup.pubsub.{Publisher, PubsubConnectionConfig, Serde}
import sttp.client4.Backend

import zio.test.*
import zio.{NonEmptyChunk, Task, ZIO, ZLayer, durationInt}
object PubAndSubSpec extends ZIOSpecDefault with HttpClientBackendPlatformSpecific {
  val connection = PubsubConnectionConfig.Emulator(GcpProject("any"), "localhost", 8085)

  val layer = emulatorBackendLayer(connection) >+> ZLayer.fromZIO {
    for {
      backend <- ZIO.service[Backend[Task]]
      subscriber <- HttpSubscriber.make[Any, String](
                      connection = connection,
                      backend = backend,
                      tokenProvider = TokenProvider.noTokenProvider,
                    )
      publisher = HttpPublisher.make[Any, String](
                    connection = connection,
                    topic = "test",
                    serializer = Serde.utf8String,
                    backend = backend,
                    tokenProvider = TokenProvider.noTokenProvider,
                  )
    } yield (publisher, subscriber)
  }
  override def spec: Spec[Any, Any] = suite("PubAndSubSpec")(
    test("Publish and consume") {
      for {
        _      <- createTopicWithSubscription(TopicName("any", "test"), SubscriptionName("any", "test_sub"))
        _       = println("topic and sub created")
        (p, s) <- ZIO.service[(Publisher[Any, String], Subscriber)]
        f <- s.subscribe("test_sub", Serde.utf8String)
               .take(20)
               .runForeach { case (m, ack) => zio.Console.printLine(s"received message: ${m.data}") *> ack.ack() }
               .timeout(10.seconds)
               .fork
        _ <- ZIO.foreachParDiscard(1 to 20)(i =>
               p.publish(NonEmptyChunk.single(PublishMessage(s"test message $i", None, Map.empty)))
             )
        _ <- f.join
      } yield assertCompletes
    }
  ).provide(layer)
}
