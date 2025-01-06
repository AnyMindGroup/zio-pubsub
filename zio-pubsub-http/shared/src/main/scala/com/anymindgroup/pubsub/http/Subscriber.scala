package com.anymindgroup.pubsub.http

import java.util.Base64

import com.anymindgroup.gcp.auth.{AuthedBackend, Token, TokenProvider}
import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.gcp.pubsub.v1.schemas.PubsubMessage
import com.anymindgroup.pubsub.model.{MessageId, OrderingKey}
import com.anymindgroup.pubsub.sub.{AckId, RawReceipt, ReceivedMessage, Subscriber}
import com.anymindgroup.pubsub.{AckReply, PubsubConnectionConfig}
import sttp.client4.Backend

import zio.stream.ZStream
import zio.{Cause, Chunk, NonEmptyChunk, Queue, Schedule, Scope, Task, UIO, ZIO}

class HttpSubscriber private[http] (
  backend: Backend[Task],
  projectId: String,
  maxMessagesPerPull: Int,
  ackQueue: Queue[(String, Boolean)],
  retrySchedule: Schedule[Any, Throwable, ?],
) extends Subscriber {
  private def processAckQueue(chunkSizeLimit: Option[Int], subscriptionId: String): UIO[Option[Cause[Throwable]]] =
    chunkSizeLimit
      .fold(ackQueue.takeAll)(ackQueue.takeBetween(1, _))
      .flatMap { c =>
        val (ackIds, nackIds) = c.partitionMap {
          case (id, true)  => Left(id)
          case (id, false) => Right(id)
        }

        (
          NonEmptyChunk.fromChunk(ackIds).map(sendAck(_, subscriptionId)),
          NonEmptyChunk.fromChunk(nackIds).map(sendNack(_, subscriptionId)),
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

  private def sendNack(nackIds: NonEmptyChunk[String], subscriptionId: String) =
    p.Subscriptions
      .modifyAckDeadline(
        projectsId = projectId,
        subscriptionsId = subscriptionId,
        request = s.ModifyAckDeadlineRequest(nackIds, ackDeadlineSeconds = 0),
      )
      .send(backend)
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(nackIds.map((_, false))).as(Some(c)))

  private def sendAck(ackIds: NonEmptyChunk[String], subscriptionId: String) =
    p.Subscriptions
      .acknowledge(
        projectsId = projectId,
        subscriptionsId = subscriptionId,
        request = s.AcknowledgeRequest(ackIds),
      )
      .send(backend)
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(ackIds.map((_, true))).as(Some(c)))

  private[http] def pull(
    subscriptionName: String,
    returnImmediately: Option[Boolean] = None,
  ): ZIO[Any, Throwable, Chunk[(ReceivedMessage[Array[Byte]], AckReply)]] =
    p.Subscriptions
      .pull(
        projectsId = projectId,
        subscriptionsId = subscriptionName,
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
                          case None        => Array.empty[Byte]
                          case Some(value) => Base64.getDecoder().decode(value)
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

  override def subscribeRaw(subscriptionName: String): ZStream[Any, Throwable, RawReceipt] = {
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
  def make[R, E](
    connection: PubsubConnectionConfig,
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
    maxMessagesPerPull: Int = 100,
    retrySchedule: Schedule[Any, Throwable, ?] = Schedule.recurs(5),
  ): ZIO[Scope, Nothing, HttpSubscriber] =
    for {
      ackQueue <- ZIO.acquireRelease(Queue.unbounded[(String, Boolean)])(_.shutdown)
    } yield connection match {
      case PubsubConnectionConfig.Cloud(project) =>
        new HttpSubscriber(
          projectId = project.name,
          backend = AuthedBackend(tokenProvider, backend),
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
        )
      case config @ PubsubConnectionConfig.Emulator(project, _, _) =>
        new HttpSubscriber(
          projectId = project.name,
          backend = EmulatorBackend(backend, config),
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
        )
    }
}
