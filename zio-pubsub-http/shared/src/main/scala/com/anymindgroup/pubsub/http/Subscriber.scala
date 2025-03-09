package com.anymindgroup.pubsub.http

import java.util.Base64
import java.util.Base64.Decoder

import com.anymindgroup.gcp.auth.{
  Token,
  TokenProvider,
  TokenProviderException,
  defaultAccessTokenBackend,
  toAuthedBackend,
}
import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.gcp.pubsub.v1.schemas.PubsubMessage
import com.anymindgroup.pubsub.*
import sttp.client4.Backend

import zio.stream.ZStream
import zio.{Cause, Chunk, NonEmptyChunk, Queue, Schedule, Scope, Task, UIO, ZIO}

class HttpSubscriber private[http] (
  backend: Backend[Task],
  maxMessagesPerPull: Int,
  ackQueue: Queue[(String, Boolean)],
  retrySchedule: Schedule[Any, Throwable, ?],
  base64Decoder: Decoder,
) extends Subscriber {
  private def processAckQueue(chunkSizeLimit: Option[Int], subName: SubscriptionName): UIO[Option[Cause[Throwable]]] =
    chunkSizeLimit
      .fold(ackQueue.takeAll)(ackQueue.takeBetween(1, _))
      .flatMap { c =>
        val (ackIds, nackIds) = c.partitionMap {
          case (id, true)  => Left(id)
          case (id, false) => Right(id)
        }

        (
          NonEmptyChunk.fromChunk(ackIds).map(sendAck(_, subName)),
          NonEmptyChunk.fromChunk(nackIds).map(sendNack(_, subName)),
        ) match {
          case (Some(sendAckReq), Some(sendNackReq)) =>
            (sendAckReq <&> sendNackReq).map {
              case (Some(c1), Some(c2)) => Some(c1 && c2)
              case (c1, c2)             => c1.orElse(c2)
            }
          case (Some(sendAckReq), _)  => sendAckReq
          case (_, Some(sendNackReq)) => sendNackReq
          case _                      => ZIO.none
        }
      }

  private def sendNack(nackIds: NonEmptyChunk[String], subName: SubscriptionName) =
    p.Subscriptions
      .modifyAckDeadline(
        projectsId = subName.projectId,
        subscriptionsId = subName.subscription,
        request = s.ModifyAckDeadlineRequest(nackIds, ackDeadlineSeconds = 0),
      )
      .send(backend)
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(nackIds.map((_, false))).as(Some(c)))

  private def sendAck(ackIds: NonEmptyChunk[String], subName: SubscriptionName) =
    p.Subscriptions
      .acknowledge(
        projectsId = subName.projectId,
        subscriptionsId = subName.subscription,
        request = s.AcknowledgeRequest(ackIds),
      )
      .send(backend)
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(ackIds.map((_, true))).as(Some(c)))

  private[http] def pull(
    subscriptionName: SubscriptionName,
    returnImmediately: Option[Boolean] = None,
  ): ZIO[Any, Throwable, Chunk[(ReceivedMessage[Chunk[Byte]], AckReply)]] =
    p.Subscriptions
      .pull(
        projectsId = subscriptionName.projectId,
        subscriptionsId = subscriptionName.subscription,
        request = s.PullRequest(maxMessages = maxMessagesPerPull, returnImmediately),
      )
      .send(backend)
      .flatMap { res =>
        res.body match {
          case Left(value) => ZIO.fail(new Throwable(value))
          case Right(value) =>
            ZIO.succeed(
              value.receivedMessages
                .getOrElse(Chunk.empty)
                .collect {
                  case s.ReceivedMessage(
                        Some(ackId),
                        Some(PubsubMessage(data, attrs, Some(mId), Some(ts), orderingKey)),
                        deliveryAttempt,
                      ) =>
                    (
                      ReceivedMessage(
                        meta = ReceivedMessage.Metadata(
                          messageId = MessageId(mId),
                          ackId = AckId(ackId),
                          publishTime = ts.toInstant(),
                          orderingKey = orderingKey.flatMap(OrderingKey.fromString(_)),
                          attributes = attrs.getOrElse(Map.empty),
                          deliveryAttempt = deliveryAttempt.getOrElse(0),
                        ),
                        data = data match {
                          case None        => Chunk.empty[Byte]
                          case Some(value) => Chunk.fromArray(base64Decoder.decode(value))
                        },
                      ),
                      new AckReply {
                        override def ack(): UIO[Unit] =
                          ackQueue.offer((ackId, true)).unit
                        override def nack(): UIO[Unit] =
                          ackQueue.offer((ackId, false)).unit
                      },
                    )
                }
            )
        }
      }

  override def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt] = {
    val pullStream = ZStream.repeatZIOChunk(pull(subscriptionName))

    val ackStream: ZStream[Any, Throwable, Unit] = ZStream
      .unfoldZIO(())(_ =>
        processAckQueue(Some(1024), subscriptionName).flatMap {
          case None    => ZIO.some(((), ()))
          case Some(c) => ZIO.failCause(c)
        }
      )

    pullStream.drainFork(ackStream).onError(_ => processAckQueue(None, subscriptionName)).retry(retrySchedule)
  }
}

object HttpSubscriber {
  object defaults:
    val maxMessagesPerPull: Int                      = 100
    val retrySchedule: Schedule[Any, Throwable, Any] = Schedule.recurs(5)

  private[pubsub] def makeFromAuthedBackend(
    connection: PubsubConnectionConfig,
    authedBackend: Backend[Task],
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
  ): ZIO[Scope, Nothing, HttpSubscriber] =
    for {
      ackQueue <- ZIO.acquireRelease(Queue.unbounded[(String, Boolean)])(_.shutdown)
    } yield connection match {
      case PubsubConnectionConfig.Cloud =>
        new HttpSubscriber(
          backend = authedBackend,
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
          base64Decoder = Base64.getDecoder(),
        )
      case config: PubsubConnectionConfig.Emulator =>
        new HttpSubscriber(
          backend = EmulatorBackend(authedBackend, config),
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
          base64Decoder = Base64.getDecoder(),
        )
    }

  def make(
    connection: PubsubConnectionConfig,
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
  ): ZIO[Scope, Nothing, HttpSubscriber] =
    makeFromAuthedBackend(
      connection = connection,
      authedBackend = toAuthedBackend(tokenProvider, backend),
      maxMessagesPerPull = maxMessagesPerPull,
      retrySchedule = retrySchedule,
    )

  def makeWithDefaultTokenProvider(
    connection: PubsubConnectionConfig,
    backend: Backend[Task],
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
    lookupComputeMetadataFirst: Boolean = false,
    tokenRefreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    tokenRefreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, TokenProviderException, HttpSubscriber] =
    TokenProvider
      .defaultAccessTokenProvider(
        backend = backend,
        lookupComputeMetadataFirst = lookupComputeMetadataFirst,
        refreshRetrySchedule = tokenRefreshRetrySchedule,
        refreshAtExpirationPercent = tokenRefreshAtExpirationPercent,
      )
      .flatMap: tokenProvider =>
        make(
          connection = connection,
          backend = backend,
          tokenProvider = tokenProvider,
          maxMessagesPerPull = maxMessagesPerPull,
          retrySchedule = retrySchedule,
        )

  def makeWithDefaultBackend(
    connection: PubsubConnectionConfig,
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
    lookupComputeMetadataFirst: Boolean = false,
    tokenRefreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    tokenRefreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, Throwable, HttpSubscriber] =
    defaultAccessTokenBackend(
      lookupComputeMetadataFirst = lookupComputeMetadataFirst,
      refreshRetrySchedule = tokenRefreshRetrySchedule,
      refreshAtExpirationPercent = tokenRefreshAtExpirationPercent,
    ).flatMap: authedBackend =>
      makeFromAuthedBackend(
        connection = connection,
        authedBackend = authedBackend,
        maxMessagesPerPull = maxMessagesPerPull,
        retrySchedule = retrySchedule,
      )
}
