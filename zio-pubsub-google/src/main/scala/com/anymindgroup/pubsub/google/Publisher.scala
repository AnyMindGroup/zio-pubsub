package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.google.PubsubConnectionConfig.{Cloud, Emulator}
import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.pub.*
import com.anymindgroup.pubsub.serde.Serializer
import com.google.cloud.pubsub.v1.Publisher as GPublisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage as GPubsubMessage

import zio.{RIO, Scope, ZIO}

object Publisher {
  def make[R, E](
    connection: PubsubConnectionConfig,
    topic: Topic[R, E],
    enableOrdering: Boolean,
  ): RIO[Scope, Publisher[R, E]] =
    make(PublisherConfig.forTopic(connection, topic, enableOrdering), topic.serde)

  def make[R, E](config: PublisherConfig, ser: Serializer[R, E]): RIO[Scope, Publisher[R, E]] =
    makeUnderlyingPublisher(config).map(p => new GooglePublisher(p, ser))

  private[pubsub] def makeUnderlyingPublisher: RIO[Scope & PublisherConfig, GPublisher] = for {
    config    <- ZIO.service[PublisherConfig]
    publisher <- makeUnderlyingPublisher(config)
  } yield publisher

  private[pubsub] def makeUnderlyingPublisher(config: PublisherConfig): RIO[Scope, GPublisher] = ZIO.acquireRelease {
    for {
      builder <- config.connection match {
                   case _: Cloud => ZIO.attempt(GPublisher.newBuilder(config.topicId))
                   case emulator: Emulator =>
                     for {
                       (channelProvider, credentialsProvider) <- PubsubConnectionConfig.createEmulatorSettings(emulator)
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
