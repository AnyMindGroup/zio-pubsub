package com.anymindgroup.pubsub.http

import java.util.Base64

import com.anymindgroup.gcp.auth.{AuthedBackend, Token, TokenProvider}
import com.anymindgroup.pubsub.http.resources.projects as p
import com.anymindgroup.pubsub.http.schemas as s
import com.anymindgroup.pubsub.model.{MessageId, OrderingKey}
import com.anymindgroup.pubsub.sub.{AckId, RawReceipt, ReceivedMessage, Subscriber}
import com.anymindgroup.pubsub.{AckReply, PubsubConnectionConfig}
import sttp.client4.Backend

import zio.stream.ZStream
import zio.{Cause, Chunk, Queue, Schedule, Task, UIO, ZIO}

class HttpSubscriber[R, E] private[http] (
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
        val (ackIds, nackIds) = c.toList.partitionMap {
          case (id, true)  => Left(id)
          case (id, false) => Right(id)
        }

        val ackReq =
          if (ackIds.nonEmpty)
            Some(
              p.Subscriptions
                .acknowledge(
                  projectsId = projectId,
                  subscriptionsId = subscriptionId,
                  request = s.AcknowledgeRequest(ackIds),
                )
                .send(backend)
                .as(None)
                .catchAllCause(c => ackQueue.offerAll(ackIds.map((_, true))).as(Some(c)))
            )
          else None

        val nackReq =
          if (nackIds.nonEmpty)
            Some(
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
            )
          else None

        (ackReq, nackReq) match {
          case (Some(sendAck), Some(sendNack)) =>
            (sendAck <&> sendNack).map {
              case (Some(c1), Some(c2)) => Some(c1 && c2)
              case (c1, c2)             => c1.orElse(c2)
            }
          case (Some(sendAck), _)  => sendAck
          case (_, Some(sendNack)) => sendNack
          case _                   => ZIO.none
        }
      }

  override def subscribeRaw(subscriptionName: String): ZStream[Any, Throwable, RawReceipt] = {
    val pullStream = ZStream
      .repeatZIOChunk(
        backend
          .send(
            p.Subscriptions.pull(
              projectsId = projectId,
              subscriptionsId = subscriptionName,
              request = s.PullRequest(maxMessages = maxMessagesPerPull),
            )
          )
          .flatMap { res =>
            res.body match {
              case Left(value) => ZIO.fail(new Throwable(value))
              case Right(value) =>
                ZIO.succeed(
                  Chunk.fromIterable(
                    value.receivedMessages.toList.flatten.collect {
                      case s.ReceivedMessage(Some(ackId), Some(message), deliveryAttempt) =>
                        (
                          ReceivedMessage(
                            meta = ReceivedMessage.Metadata(
                              messageId = MessageId(message.messageId),
                              ackId = AckId(ackId),
                              publishTime = message.publishTime.toInstant(),
                              orderingKey = message.orderingKey.flatMap(OrderingKey.fromString(_)),
                              attributes = message.attributes.getOrElse(Map.empty),
                              deliveryAttempt = deliveryAttempt.getOrElse(0),
                            ),
                            data = message.data match {
                              case None        => Array.empty[Byte]
                              case Some(value) => Base64.getDecoder().decode(value)
                            },
                          ),
                          new AckReply {
                            override def ack(): UIO[Unit]  = ackQueue.offer((ackId, true)).unit
                            override def nack(): UIO[Unit] = ackQueue.offer((ackId, false)).unit
                          },
                        )
                    }
                  )
                )
            }
          }
      )

    val ackStream: ZStream[Any, Throwable, Unit] = ZStream
      .unfoldZIO(())(_ =>
        processAckQueue(Some(1024), subscriptionName).flatMap {
          case None    => ZIO.some(((), ()))
          case Some(c) => ZIO.failCause(c)
        }
      )

    pullStream.drainFork(ackStream).retry(retrySchedule)
  }

}

object HttpSubscriber {
  def make[R, E](
    connection: PubsubConnectionConfig,
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
    maxMessagesPerPull: Int = 100,
    retrySchedule: Schedule[Any, Throwable, ?] = Schedule.recurs(5),
  ): ZIO[Any, Nothing, Subscriber] =
    for {
      ackQueue <- Queue.unbounded[(String, Boolean)]
    } yield connection match {
      case PubsubConnectionConfig.Cloud(project) =>
        new HttpSubscriber[R, E](
          projectId = project.name,
          backend = AuthedBackend(tokenProvider, backend),
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
        )
      case config @ PubsubConnectionConfig.Emulator(project, _, _) =>
        new HttpSubscriber[R, E](
          projectId = project.name,
          backend = EmulatorBackend(backend, config),
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
        )
    }
}
