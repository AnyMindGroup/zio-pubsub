package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.pub.*
import com.anymindgroup.pubsub.serde.Serializer
import com.google.cloud.pubsub.v1.Publisher as GPublisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage as GPubsubMessage

import zio.{NonEmptyChunk, RIO, Scope, ZIO}

object Publisher {
  def make[R, E](
    topicName: TopicName,
    encoding: Encoding,
    serialzer: Serializer[R, E],
    enableOrdering: Boolean,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, Publisher[R, E]] =
    make(PublisherConfig(topicName, encoding, enableOrdering), serialzer, connection)

  def make[R, E](
    config: PublisherConfig,
    serialzer: Serializer[R, E],
    connection: PubsubConnectionConfig,
  ): RIO[Scope, Publisher[R, E]] =
    makeUnderlyingPublisher(config, connection).map(p => new GooglePublisher(p, serialzer))

  private[pubsub] def makeUnderlyingPublisher(
    config: PublisherConfig,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GPublisher] = ZIO.acquireRelease {
    for {
      builder <- connection match {
                   case PubsubConnectionConfig.Cloud => ZIO.attempt(GPublisher.newBuilder(config.topicId))
                   case emulator: PubsubConnectionConfig.Emulator =>
                     for {
                       (channelProvider, credentialsProvider) <- Emulator.createEmulatorSettings(emulator)
                       p <- ZIO.attempt(
                              GPublisher
                                .newBuilder(config.topicId)
                                .setChannelProvider(channelProvider)
                                .setCredentialsProvider(credentialsProvider)
                            )
                     } yield p
                 }
      publisher <- ZIO.attempt(builder.setEnableMessageOrdering(config.enableOrdering).build())
    } yield publisher
  }(p =>
    ZIO.logInfo(s"Shutting down publisher for topic ${config.topicName}...") *> ZIO.succeed {
      p.shutdown()
      p.awaitTermination(30, TimeUnit.SECONDS)
    }
  )

  private[pubsub] def toPubsubMessage(
    data: ByteString,
    attributes: Map[String, String],
    orderingKey: Option[OrderingKey],
  ) = {
    val builder = GPubsubMessage.newBuilder.putAllAttributes(attributes.asJava).setData(data)
    orderingKey.map(v => builder.setOrderingKey(v.value)).getOrElse(builder).build()
  }

}

class GooglePublisher[R, E](publisher: GPublisher, serde: Serializer[R, E]) extends Publisher[R, E] {

  override def publish(messages: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]] =
    ZIO
      .foreach(messages) { message =>
        toPubsubMessage(message).map(publisher.publish(_))
      }
      .flatMap { futures =>
        ZIO.foreachPar(futures)(f => ZIO.fromFutureJava(f).map(MessageId(_)))
      }

  override def publish(event: PublishMessage[E]): ZIO[R, Throwable, MessageId] =
    for {
      msg       <- toPubsubMessage(event)
      messageId <- ZIO.fromFutureJava(publisher.publish(msg))
    } yield MessageId(messageId)

  private def toPubsubMessage(e: PublishMessage[E]): ZIO[R, Throwable, GPubsubMessage] =
    serde
      .serialize(e.data)
      .map(serializedData =>
        Publisher.toPubsubMessage(ByteString.copyFrom(serializedData), e.attributes, e.orderingKey)
      )

}
