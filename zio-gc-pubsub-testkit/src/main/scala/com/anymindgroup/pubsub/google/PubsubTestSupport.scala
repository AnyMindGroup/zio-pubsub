package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.google.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.{CirceSerde, VulcanSerde}
import com.anymindgroup.pubsub.sub.*
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.{Publisher as GPublisher, SubscriptionAdminClient, TopicAdminClient}
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, ReceivedMessage, Subscription as GSubscription, SubscriptionName, TopicName}
import vulcan.Codec
import vulcan.generic.*

import zio.stream.ZStream
import zio.test.Gen
import zio.{Duration, RIO, RLayer, Scope, Task, ZIO, ZLayer, durationInt}

object PubsubTestSupport {
  final case class TestEvent(name: String, age: Int)
  object TestEvent {
    implicit val avroCodec: Codec[TestEvent]          = Codec.derive[TestEvent]
    val avroCodecSchema: String                       = TestEvent.avroCodec.schema.map(_.toString()).toOption.get
    implicit val jsonCodec: io.circe.Codec[TestEvent] = io.circe.generic.semiauto.deriveCodec[TestEvent]
  }

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
  ): RIO[Scope & PubsubConnectionConfig.Emulator, ZStream[Any, Throwable, ReceivedMessage]] =
    for {
      connection    <- ZIO.service[PubsubConnectionConfig.Emulator]
      randomSubName <- Gen.alphaNumericChar.runCollectN(10).map(_.mkString)
      stream <- Subscriber
                  .makeTempUniqueRawSubscriptionStream(
                    connection = connection,
                    topicName = topicName,
                    subscriptionName = s"test_${randomSubName}",
                    subscriptionFilter = None,
                    maxTtl = Duration.Infinity,
                    enableOrdering = enableOrdering,
                  )
                  .map(_._2)
    } yield stream.via(Pipeline.autoAckRawPipeline)

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
  val schemaRegistryGen: Gen[Any, SchemaRegistry] =
    (Gen.alphaNumericStringBounded(5, 20) <*> Gen.elements((SchemaType.Avro, TestEvent.avroCodecSchema))).map {
      case (id, (schemaType, schemaDefinition)) =>
        SchemaRegistry("schema_" + id, schemaType, ZIO.succeed(schemaDefinition))
    }
  val schemaSettingsGen: Gen[Any, SchemaSettings] = (encodingGen <*> Gen.option(schemaRegistryGen))
    .map(setting => SchemaSettings(setting._1, setting._2))
    .filter(setting => setting.encoding == Encoding.Json || setting.schema.isDefined)

  val topicConfigsGen: Gen[PubsubConnectionConfig.Emulator, Topic[Any, TestEvent]] = for {
    schemaSetting <- schemaSettingsGen
    serde = schemaSetting match {
              case SchemaSettings(Encoding.Binary, _) => VulcanSerde.fromAvroCodec(TestEvent.avroCodec, Encoding.Binary)
              case SchemaSettings(Encoding.Json, Some(_)) =>
                VulcanSerde.fromAvroCodec(TestEvent.avroCodec, Encoding.Json)
              case SchemaSettings(Encoding.Json, None) => CirceSerde.fromCirceCodec(TestEvent.jsonCodec)
            }
    topicName <- topicNameGen
  } yield Topic(topicName.getTopic(), schemaSetting, serde)

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
  ))
}
