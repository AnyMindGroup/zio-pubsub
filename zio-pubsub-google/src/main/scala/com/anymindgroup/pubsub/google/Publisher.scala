package com.anymindgroup.pubsub
package google

import scala.jdk.CollectionConverters.*

import com.google.cloud.pubsub.v1.stub.{GrpcPublisherStub, PublisherStubSettings}
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PublishRequest, PubsubMessage as GPubsubMessage}

import zio.{Chunk, NonEmptyChunk, RIO, Scope, ZIO}

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
    makeUnderlyingPublisher(config, connection).map(p => new GooglePublisher(p, config.topicName, serialzer))

  private[pubsub] def makeUnderlyingPublisher(
    config: PublisherConfig,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GrpcPublisherStub] =
    createStub(
      connection = connection,
      builder = PublisherStubSettings.newBuilder(),
      create = GrpcPublisherStub.create,
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

class GooglePublisher[R, E](publisher: GrpcPublisherStub, topicName: TopicName, serde: Serializer[R, E])
    extends Publisher[R, E] {

  override def publish(messages: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]] =
    for {
      gMessages <- ZIO.foreach(messages)(toPubsubMessage(_)).map(_.toChunk.asJava)
      response <- ZIO.fromFutureJava(
                    publisher
                      .publishCallable()
                      .futureCall(
                        PublishRequest
                          .newBuilder()
                          .setTopic(topicName.fullName)
                          .addAllMessages(gMessages)
                          .build()
                      )
                  )
      ids <- NonEmptyChunk.fromChunk(Chunk.fromJavaIterable(response.getMessageIdsList()).map(MessageId(_))) match
               case None        => ZIO.dieMessage("Unexpected response with no message ids")
               case Some(value) => ZIO.succeed(value)
    } yield ids

  override def publish(event: PublishMessage[E]): ZIO[R, Throwable, MessageId] =
    publish(NonEmptyChunk(event)).map(_.head)

  private def toPubsubMessage(e: PublishMessage[E]): ZIO[R, Throwable, GPubsubMessage] =
    serde
      .serialize(e.data)
      .map(serializedData =>
        Publisher.toPubsubMessage(ByteString.copyFrom(serializedData.toArray), e.attributes, e.orderingKey)
      )

}
