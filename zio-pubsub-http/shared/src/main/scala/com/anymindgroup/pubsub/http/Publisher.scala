package com.anymindgroup.pubsub.http

import java.util.Base64

import com.anymindgroup.gcp.auth.{AccessToken, Token, TokenProvider, TokenProviderException, defaultAccessTokenBackend, toAuthedBackend}
import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.pubsub.*
import com.anymindgroup.pubsub.model.{MessageId, PubsubConnectionConfig, TopicName}
import sttp.client4.Backend

import zio.{Chunk, NonEmptyChunk, RIO, Schedule, Scope, Task, ZIO}

class HttpPublisher[R, E](
  serializer: Serializer[R, E],
  backend: Backend[Task],
  topic: TopicName,
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
                    data <- serializer.serialize(event.data).map(Base64.getEncoder.encodeToString)
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

object HttpPublisher {
  def make[R, E](
    connection: PubsubConnectionConfig,
    topic: String,
    serializer: Serializer[R, E],
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
  ): HttpPublisher[R, E] =
    connection match {
      case PubsubConnectionConfig.Cloud(project) =>
        new HttpPublisher[R, E](
          serializer = serializer,
          topic = TopicName(projectId = project.name, topic = topic),
          backend = toAuthedBackend(tokenProvider, backend),
        )
      case config @ PubsubConnectionConfig.Emulator(project, _, _) =>
        new HttpPublisher[R, E](
          serializer = serializer,
          topic = TopicName(projectId = project.name, topic = topic),
          backend = EmulatorBackend(backend, config),
        )
    }

  def make[R, E](
    connection: PubsubConnectionConfig,
    topic: Topic[R, E],
    backend: Backend[Task],
    tokenProvider: TokenProvider[AccessToken],
  ): HttpPublisher[R, E] = make(connection, topic.name, topic.serde, backend, tokenProvider)

  def makeWithDefaultTokenProvider[R, E](
    connection: PubsubConnectionConfig,
    topic: Topic[R, E],
    backend: Backend[Task],
    lookupComputeMetadataFirst: Boolean = false,
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, TokenProviderException, HttpPublisher[R, E]] =
    TokenProvider
      .defaultAccessTokenProvider(
        backend = backend,
        lookupComputeMetadataFirst = lookupComputeMetadataFirst,
        refreshRetrySchedule = refreshRetrySchedule,
        refreshAtExpirationPercent = refreshAtExpirationPercent,
      )
      .map: tokenProvider =>
        make(connection, topic.name, topic.serde, backend, tokenProvider)

  def makeWithDefaultAuthedBackend[R, E](
    connection: PubsubConnectionConfig,
    topic: Topic[R, E],
    lookupComputeMetadataFirst: Boolean = false,
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, Throwable, HttpPublisher[R, E]] =
    defaultAccessTokenBackend(
      lookupComputeMetadataFirst = lookupComputeMetadataFirst,
      refreshRetrySchedule = refreshRetrySchedule,
      refreshAtExpirationPercent = refreshAtExpirationPercent,
    ).flatMap: backend =>
      makeWithDefaultTokenProvider(connection, topic, backend)
}
