package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.sub.*
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.{Publisher as GPublisher, SubscriptionAdminClient, TopicAdminClient}
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, Subscription as GSubscription, SubscriptionName, TopicName}

import zio.stream.ZStream
import zio.test.Gen
import zio.{Duration, RIO, RLayer, Scope, Task, ZIO, ZLayer, durationInt}

object PubsubTestSupport {
  def emulatorConnectionConfig(
    project: GcpProject = sys.env.get("PUBSUB_EMULATOR_GCP_PROJECT").map(GcpProject(_)).getOrElse(GcpProject("any")),
    host: String = sys.env.get("PUBSUB_EMULATOR_HOST").getOrElse("localhost:8085"),
  ): PubsubConnectionConfig.Emulator =
    PubsubConnectionConfig.Emulator(project, host)

  def emulatorConnectionConfigLayer(
    config: PubsubConnectionConfig.Emulator = emulatorConnectionConfig()
  ): ZLayer[Any, Nothing, PubsubConnectionConfig.Emulator & GcpProject] =
    ZLayer.succeed(config) ++ ZLayer.succeed(config.project)

  val topicAdminClientLayer: RLayer[Scope & PubsubConnectionConfig.Emulator, TopicAdminClient] =
    ZLayer.fromZIO {
      for {
        config      <- ZIO.service[PubsubConnectionConfig.Emulator]
        adminClient <- TopicAdmin.makeClient(config)
      } yield adminClient
    }

  val subscriptionAdminClientLayer: RLayer[Scope & PubsubConnectionConfig.Emulator, SubscriptionAdminClient] =
    ZLayer.fromZIO {
      for {
        config      <- ZIO.service[PubsubConnectionConfig.Emulator]
        adminClient <- SubscriptionAdmin.makeClient(config)
      } yield adminClient
    }

  val adminLayer: RLayer[Scope & PubsubConnectionConfig.Emulator, TopicAdminClient & SubscriptionAdminClient] =
    topicAdminClientLayer ++ subscriptionAdminClientLayer

  def createTopicWithSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
  ): RIO[SubscriptionAdminClient & TopicAdminClient, Unit] =
    createTopic(topicName) *> createSubscription(topicName, subscriptionName)

  def createSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
  ): RIO[SubscriptionAdminClient, Unit] = for {
    subAdminClient <- ZIO.service[SubscriptionAdminClient]
    subscription = GSubscription
                     .newBuilder()
                     .setTopic(topicName.toString)
                     .setName(subscriptionName.toString)
                     .build()
    _ <- ZIO.attempt(subAdminClient.createSubscription(subscription))
  } yield ()

  def createTopic(topicName: TopicName): RIO[TopicAdminClient, Unit] = for {
    topicAdminClient <- ZIO.service[TopicAdminClient]
    _                <- ZIO.attempt(topicAdminClient.createTopic(topicName))
  } yield ()

  def topicExists(topicName: TopicName): RIO[TopicAdminClient, Boolean] = for {
    topicAdmin <- ZIO.service[TopicAdminClient]
    res <- ZIO.attempt(topicAdmin.getTopic(topicName)).as(true).catchSome {
             case _: com.google.api.gax.rpc.NotFoundException => ZIO.succeed(false)
           }
  } yield res

  def publishEvent[E](
    event: E,
    publisher: GPublisher,
    encode: E => Array[Byte] = (e: E) => e.toString.getBytes,
  ): Task[Seq[String]] =
    publishEvents(Seq(event), publisher, encode)

  def publishEvents[E](
    events: Seq[E],
    publisher: GPublisher,
    encode: E => Array[Byte] = (e: E) => e.toString.getBytes,
  ): Task[Seq[String]] = {
    val messages = events.map { data =>
      val dataArr = encode(data)
      PubsubMessage.newBuilder
        .setData(ByteString.copyFrom(dataArr))
        .build
    }
    ZIO.foreach(messages)(e => ZIO.fromFutureJava(publisher.publish(e)))
  }

  def publishBatches[E](
    publisher: GPublisher,
    amount: Int,
    event: Int => E,
  ): ZStream[Any, Throwable, String] =
    ZStream
      .fromIterable((0 until amount).map(event))
      .mapZIO { e =>
        publishEvent(e, publisher)
      }
      .flatMap(ZStream.fromIterable(_))

  val topicNameGen: Gen[PubsubConnectionConfig.Emulator, TopicName] = for {
    connection <- Gen.fromZIO(ZIO.service[PubsubConnectionConfig.Emulator])
    topic      <- Gen.alphaNumericStringBounded(10, 10).map("topic_" + _)
  } yield TopicName.of(connection.project.name, topic)

  val subscriptionNameGen: Gen[PubsubConnectionConfig.Emulator, SubscriptionName] = for {
    connection     <- Gen.fromZIO(ZIO.service[PubsubConnectionConfig.Emulator])
    subscriptionId <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _)
  } yield SubscriptionName.of(connection.project.name, subscriptionId)

  val topicWithSubscriptionGen: Gen[PubsubConnectionConfig.Emulator, (TopicName, SubscriptionName)] = for {
    topicName        <- topicNameGen
    subscriptionName <- subscriptionNameGen
  } yield (topicName, subscriptionName)

  def someTopicWithSubscriptionName: ZIO[PubsubConnectionConfig.Emulator, Nothing, (TopicName, SubscriptionName)] =
    topicWithSubscriptionGen.runHead.map(_.get)

  def createSomeSubscriptionRawStream(
    topicName: String,
    enableOrdering: Boolean = false,
  ): RIO[Scope & PubsubConnectionConfig.Emulator, ZStream[Any, Throwable, ReceivedMessage.Raw]] =
    for {
      connection    <- ZIO.service[PubsubConnectionConfig.Emulator]
      randomSubName <- Gen.alphaNumericChar.runCollectN(10).map(_.mkString)
      stream <- google.Subscriber
                  .makeTempRawStreamingPullSubscription(
                    connection = connection,
                    topicName = topicName,
                    subscriptionName = s"test_${randomSubName}",
                    subscriptionFilter = None,
                    maxTtl = Duration.Infinity,
                    enableOrdering = enableOrdering,
                  )
    } yield stream.via(Pipeline.autoAckPipeline)

  def findSubscription(
    subscriptionName: String
  ): RIO[GcpProject & SubscriptionAdminClient, Option[GSubscription]] = for {
    client         <- ZIO.service[SubscriptionAdminClient]
    subscriptionId <- ZIO.serviceWith[GcpProject](g => SubscriptionName.of(g.name, subscriptionName))
    result <- ZIO.attempt(client.getSubscription(subscriptionId)).map(Some(_)).catchSome { case _: NotFoundException =>
                ZIO.none
              }
  } yield result

  val encodingGen: Gen[Any, Encoding] = Gen.fromIterable(List(Encoding.Binary, Encoding.Json))

  def subscriptionsConfigsGen(topicName: String): Gen[PubsubConnectionConfig.Emulator, Subscription] = (for {
    filter <- Gen
                .option(
                  Gen.mapOf(Gen.alphaNumericString, Gen.alphaNumericString).map(SubscriberFilter.matchingAttributes)
                )
    enableOrdering <- Gen.boolean
    expiration     <- Gen.option(Gen.finiteDuration(24.hours, 30.days))
    name           <- subscriptionNameGen
  } yield Subscription(
    topicName = topicName,
    name = name.getSubscription(),
    filter = filter,
    enableOrdering = enableOrdering,
    expiration = expiration,
    deadLettersSettings = None,
  ))
}
