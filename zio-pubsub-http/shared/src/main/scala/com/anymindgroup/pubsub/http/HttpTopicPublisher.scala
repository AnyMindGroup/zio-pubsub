package com.anymindgroup.pubsub.http

import java.util.Base64
import java.util.Base64.Encoder

import com.anymindgroup.gcp.auth.{
  Token,
  TokenProvider,
  TokenProviderException,
  defaultAccessTokenBackend,
  toAuthedBackend,
}
import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.http.httpBackendScoped
import com.anymindgroup.pubsub.*
import sttp.client4.Backend

import zio.{Chunk, NonEmptyChunk, RIO, Scope, Task, ZIO}

class HttpTopicPublisher[R, E] private[http] (
  serializer: Serializer[R, E],
  backend: Backend[Task],
  topic: TopicName,
  base64Encoder: Encoder,
) extends Publisher[R, E] {

  override def publish(events: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]] =
    for {
      request  <- toRequestBody(events)
      response <- request.send(backend)
      ids <- ZIO.fromEither {
               if (response.isSuccess) {
                 response.body.map(r => NonEmptyChunk.fromChunk(r.messageIds.getOrElse(Chunk.empty))) match {
                   case Right(Some(msgIds)) => Right(msgIds.map(MessageId(_)))
                   case Right(_)            => Left(new Throwable("Missing id in response"))
                   case Left(err)           => Left(new Throwable(err))
                 }
               } else Left(new Throwable(s"Failed with ${response.code} ${response.statusText}"))
             }
    } yield ids

  override def publish(event: PublishMessage[E]): ZIO[R, Throwable, MessageId] =
    publish(NonEmptyChunk.single(event)).map(_.head)

  private def toRequestBody(events: NonEmptyChunk[PublishMessage[E]]) = for {
    messages <- ZIO.foreach(events) { event =>
                  for {
                    data <- serializer.serialize(event.data).map(c => base64Encoder.encodeToString(c.toArray))
                  } yield s.PubsubMessage(
                    data = Some(data),
                    orderingKey = event.orderingKey.map(_.value),
                    attributes = if (event.attributes.nonEmpty) Some(event.attributes) else None,
                  )
                }
  } yield p.Topics.publish(
    projectsId = topic.projectId,
    topicsId = topic.topic,
    request = s.PublishRequest(messages),
  )
}

object HttpTopicPublisher {
  private def makeFromAuthedBackend[R, E](
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    serializer: Serializer[R, E],
    authedBackend: Backend[Task],
  ): HttpTopicPublisher[R, E] =
    connection match {
      case PubsubConnectionConfig.Cloud =>
        new HttpTopicPublisher[R, E](
          serializer = serializer,
          topic = topicName,
          backend = authedBackend,
          base64Encoder = Base64.getEncoder(),
        )
      case emulator: PubsubConnectionConfig.Emulator =>
        new HttpTopicPublisher[R, E](
          serializer = serializer,
          topic = topicName,
          backend = EmulatorBackend(authedBackend, emulator),
          base64Encoder = Base64.getEncoder(),
        )
    }

  def make[R, E](
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    serializer: Serializer[R, E],
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
  ): HttpTopicPublisher[R, E] =
    makeFromAuthedBackend(connection, topicName, serializer, toAuthedBackend(tokenProvider, backend))

  def makeWithDefaultTokenProvider[R, E](
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    serializer: Serializer[R, E],
    backend: Backend[Task],
    authConfig: AuthConfig = AuthConfig.default,
  ): ZIO[Scope, TokenProviderException, HttpTopicPublisher[R, E]] =
    TokenProvider
      .defaultAccessTokenProvider(
        backend = backend,
        lookupComputeMetadataFirst = authConfig.lookupComputeMetadataFirst,
        refreshRetrySchedule = authConfig.tokenRefreshRetrySchedule,
        refreshAtExpirationPercent = authConfig.tokenRefreshAtExpirationPercent,
      )
      .map: tokenProvider =>
        make(connection, topicName, serializer, backend, tokenProvider)

  def makeWithDefaultBackend[R, E](
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    serializer: Serializer[R, E],
    authConfig: AuthConfig = AuthConfig.default,
  ): ZIO[Scope, Throwable, HttpTopicPublisher[R, E]] =
    connection match
      case emulator: PubsubConnectionConfig.Emulator =>
        httpBackendScoped().map: backend =>
          makeFromAuthedBackend(
            connection = emulator,
            topicName = topicName,
            serializer = serializer,
            authedBackend = backend,
          )
      case _ =>
        defaultAccessTokenBackend(
          lookupComputeMetadataFirst = authConfig.lookupComputeMetadataFirst,
          refreshRetrySchedule = authConfig.tokenRefreshRetrySchedule,
          refreshAtExpirationPercent = authConfig.tokenRefreshAtExpirationPercent,
        ).map: backend =>
          makeFromAuthedBackend(connection, topicName, serializer, backend)
}
