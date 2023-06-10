package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.google.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.google.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.VulcanSerde
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.pubsub.v1.SubscriptionName
import vulcan.Codec

import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, *}
import zio.{Duration, RIO, Scope, ZIO}
object SubscriberSpec extends ZIOSpecDefault {

  private val testEncoding   = Encoding.Binary
  private val enableOrdering = false

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberSpec")(
    test("create a subscription and remove after usage") {
      for {
        (connection, topicName) <- initTopicWithSchema
        (subscription, testResultA) <- ZIO.scoped {
                                         for {
                                           subscription <- Subscriber
                                                             .makeTempUniqueRawSubscriptionStream(
                                                               connection = connection,
                                                               topicName = topicName,
                                                               subscriptionName = "test_tmp",
                                                               subscriptionFilter = None,
                                                               maxTtl = Duration.Infinity,
                                                               enableOrdering = enableOrdering,
                                                             )
                                                             .map(_._1)
                                           existsOnCreation <- subscriptionExists(subscription.name)
                                         } yield (subscription, assertTrue(existsOnCreation))
                                       }
        existsAfterUsage <- subscriptionExists(subscription.name)
      } yield testResultA && assertTrue(!existsAfterUsage)
    }
  ).provideSomeShared[Scope](
    emulatorConnectionConfigLayer(),
    SubscriptionAdmin.layer,
  ) @@ TestAspect.nondeterministic

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
