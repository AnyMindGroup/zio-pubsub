package com.anymindgroup.pubsub

import java.util.Base64

import com.anymindgroup.http.HttpClientBackendPlatformSpecific
import com.anymindgroup.pubsub.http.resources.projects as p
import com.anymindgroup.pubsub.http.schemas.PublishRequest
import com.anymindgroup.pubsub.http.{EmulatorBackend, schemas as s}
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.model.PubsubConnectionConfig.GcpProject
import com.anymindgroup.pubsub.sub.*
import sttp.client4.{Backend, GenericBackend}

import zio.test.Gen
import zio.{RIO, Task, ZIO, ZLayer, durationInt}

object PubsubTestSupport extends HttpClientBackendPlatformSpecific {
  def emulatorConnectionConfig(
    project: GcpProject = sys.env.get("PUBSUB_EMULATOR_GCP_PROJECT").map(GcpProject(_)).getOrElse(GcpProject("any")),
    host: String = sys.env.get("PUBSUB_EMULATOR_HOST").getOrElse("localhost"),
    port: Int = sys.env.get("PUBSUB_EMULATOR_PORT").flatMap(_.toIntOption).getOrElse(8085),
  ): PubsubConnectionConfig.Emulator =
    PubsubConnectionConfig.Emulator(project, host, port)

  def emulatorConnectionConfigLayer(
    config: PubsubConnectionConfig.Emulator = emulatorConnectionConfig()
  ): ZLayer[Any, Nothing, PubsubConnectionConfig.Emulator & GcpProject] =
    ZLayer.succeed(config) ++ ZLayer.succeed(config.project)

  def emulatorBackendLayer: ZLayer[PubsubConnectionConfig.Emulator, Throwable, Backend[Task]] =
    httpBackendLayer() >>> ZLayer.fromFunction(EmulatorBackend(_, _))

  def createTopicWithSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
  ): RIO[GenericBackend[Task, Any], Unit] =
    createTopic(topicName) *> createSubscription(topicName, subscriptionName)

  def createSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
  ): RIO[GenericBackend[Task, Any], Unit] =
    ZIO
      .serviceWithZIO[GenericBackend[Task, Any]](
        _.send(
          p.Subscriptions.create(
            projectsId = topicName.projectId,
            subscriptionsId = subscriptionName.subscription,
            request = s.Subscription(
              name = subscriptionName.path,
              topic = topicName.topic,
            ),
          )
        )
      )
      .unit

  def createTopic(topicName: TopicName): RIO[GenericBackend[Task, Any], Unit] =
    ZIO
      .serviceWithZIO[GenericBackend[Task, Any]](
        _.send(
          p.Topics.create(
            projectsId = topicName.projectId,
            topicsId = topicName.topic,
            request = s.Topic(name = topicName.path),
          )
        )
      )
      .unit

  def topicExists(topicName: TopicName): RIO[GenericBackend[Task, Any], Boolean] = for {
    topicAdmin <- ZIO.service[GenericBackend[Task, Any]]
    res        <- topicAdmin.send(p.Topics.get(projectsId = topicName.projectId, topicsId = topicName.topic))
  } yield res.body.isRight

  def publishEvent[E](
    event: E,
    topicName: TopicName,
    encode: E => Array[Byte] = (e: E) => e.toString.getBytes,
  ): RIO[GenericBackend[Task, Any], Seq[String]] =
    publishEvents(Seq(event), topicName, encode)

  def publishEvents[E](
    events: Seq[E],
    topicName: TopicName,
    encode: E => Array[Byte] = (e: E) => e.toString.getBytes,
  ): RIO[GenericBackend[Task, Any], Seq[String]] =
    ZIO.serviceWithZIO[GenericBackend[Task, Any]](
      _.send(
        p.Topics
          .publish(
            projectsId = topicName.projectId,
            topicsId = topicName.topic,
            request = PublishRequest(
              events.map(encode).map(Base64.getEncoder.encodeToString).map(data => s.PublishMessage(data = data)).toList
            ),
          )
      ).flatMap { res =>
        res.body match {
          case Left(value)  => ZIO.fail(new Throwable(value))
          case Right(value) => ZIO.succeed(value.messageIds.toList.flatten)
        }
      }
    )
  // val messages = events.map { data =>
  //   val dataArr = encode(data)
  //   PubsubMessage.newBuilder
  //     .setData(ByteString.copyFrom(dataArr))
  //     .build
  // }
  // ZIO.foreach(messages)(e => ZIO.fromFutureJava(publisher.publish(e)))

  // def publishBatches[E](
  //   publisher: GPublisher,
  //   amount: Int,
  //   event: Int => E,
  // ): ZStream[Any, Throwable, String] =
  //   ZStream
  //     .fromIterable((0 until amount).map(event))
  //     .mapZIO { e =>
  //       publishEvent(e, publisher)
  //     }
  //     .flatMap(ZStream.fromIterable(_))

  val topicNameGen: Gen[PubsubConnectionConfig.Emulator, TopicName] = for {
    connection <- Gen.fromZIO(ZIO.service[PubsubConnectionConfig.Emulator])
    topic      <- Gen.alphaNumericStringBounded(10, 10).map("topic_" + _)
  } yield TopicName(projectId = connection.project.name, topic = topic)

  val subscriptionNameGen: Gen[PubsubConnectionConfig.Emulator, SubscriptionName] = for {
    connection     <- Gen.fromZIO(ZIO.service[PubsubConnectionConfig.Emulator])
    subscriptionId <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _)
  } yield SubscriptionName(connection.project.name, subscriptionId)

  val topicWithSubscriptionGen: Gen[PubsubConnectionConfig.Emulator, (TopicName, SubscriptionName)] = for {
    topicName        <- topicNameGen
    subscriptionName <- subscriptionNameGen
  } yield (topicName, subscriptionName)

  def someTopicWithSubscriptionName: ZIO[PubsubConnectionConfig.Emulator, Nothing, (TopicName, SubscriptionName)] =
    topicWithSubscriptionGen.runHead.map(_.get)

  // def createSomeSubscriptionRawStream(
  //   topicName: String,
  //   enableOrdering: Boolean = false,
  // ): RIO[GenericBackend[Task, Any], ZStream[Any, Throwable, ReceivedMessage.Raw]] =
  //   for {
  //     // connection    <- ZIO.service[PubsubConnectionConfig.Emulator]
  //     randomSubName <- Gen.alphaNumericChar.runCollectN(10).map(_.mkString)
  //     backend       <- ZIO.service[GenericBackend[Task, Any]]
  //     stream =
  //       ZStream.repeatZIOChunk(
  //         backend
  //           .send(
  //             p.Subscriptions.pull(projectsId = ???, subscriptionsId = ???, request = s.PullRequest(maxMessages = 100))
  //           )
  //           .flatMap { res =>
  //             res.body match
  //               case Left(value) => ZIO.fail(new Throwable(value))
  //               case Right(value) =>
  //                 ZIO.succeed(
  //                   Chunk(
  //                     value.receivedMessages.toList.flatten.collect {
  //                       case s.ReceivedMessage(Some(ackId), Some(message), deliveryAttempt) =>
  //                         ReceivedMessage(
  //                           meta = Metadata(
  //                             messageId = MessageId(message.messageId),
  //                             ackId = AckId(ackId),
  //                             publishTime = message.publishTime.toInstant(),
  //                             orderingKey = message.orderingKey.flatMap(OrderingKey.fromString(_)),
  //                             attributes = message.attributes.getOrElse(Map.empty),
  //                             deliveryAttempt = deliveryAttempt.getOrElse(0),
  //                           ),
  //                           data = message.data match
  //                             case None        => Array.empty[Byte]
  //                             case Some(value) => Base64.getDecoder().decode(value),
  //                         )
  //                     }
  //                   )
  //                 )
  //           }
  //       )
  //   } yield stream.via(Pipeline.autoAckPipeline)

  def findSubscription(
    subscription: SubscriptionName
  ): RIO[GenericBackend[Task, Any], Option[s.Subscription]] = for {
    client <- ZIO.service[GenericBackend[Task, Any]]
    result <-
      client.send(p.Subscriptions.get(projectsId = subscription.projectId, subscriptionsId = subscription.subscription))
  } yield result.body.toOption

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
    name = name.subscription,
    filter = filter,
    enableOrdering = enableOrdering,
    expiration = expiration,
    deadLettersSettings = None,
  ))
}
