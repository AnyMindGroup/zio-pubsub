package com.anymindgroup.pubsub.google

import java.util as ju
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.sub.*
import com.google.api.gax.rpc.{BidiStream as GBidiStream, ClientStream}
import com.google.cloud.pubsub.v1.stub.{GrpcSubscriberStub, SubscriberStubSettings}
import com.google.pubsub.v1.{
  ReceivedMessage as GReceivedMessage,
  StreamingPullRequest,
  StreamingPullResponse,
  SubscriptionName,
}

import zio.stream.{ZStream, ZStreamAspect}
import zio.{Cause, Chunk, Promise, Queue, RIO, Schedule, Scope, UIO, ZIO}

private[pubsub] object StreamingPullSubscriber {
  private def settingsFromConfig(
    connection: PubsubConnectionConfig
  ): RIO[Scope, SubscriberStubSettings] = for {
    builder <- connection match {
                 case _: PubsubConnectionConfig.Cloud =>
                   ZIO.attempt(
                     SubscriberStubSettings.newBuilder
                       .setTransportChannelProvider(
                         SubscriberStubSettings.defaultGrpcTransportProviderBuilder.build
                       )
                   )
                 case c: PubsubConnectionConfig.Emulator =>
                   PubsubConnectionConfig.createEmulatorSettings(c).map { case (channelProvider, credentialsProvider) =>
                     SubscriberStubSettings.newBuilder
                       .setTransportChannelProvider(channelProvider)
                       .setCredentialsProvider(credentialsProvider)
                   }
               }
  } yield builder.build()

  private[pubsub] def makeServerStream(
    stream: ServerStream[StreamingPullResponse]
  ): ZStream[Any, Throwable, GReceivedMessage] =
    ZStream
      .unfoldChunkZIO(stream.iterator())(it =>
        ZIO
          .attemptBlockingCancelable((it.hasNext()))((ZIO.succeed(stream.cancel())))
          .map {
            case true  => Some((Chunk.fromJavaIterable(it.next().getReceivedMessagesList), it))
            case false => None
          }
      )

  private def processAckQueue(
    ackQueue: Queue[(String, Boolean)],
    clientStream: ClientStream[StreamingPullRequest],
    chunkSizeLimit: Option[Int],
  ): UIO[Option[Cause[Throwable]]] =
    chunkSizeLimit
      .fold(ackQueue.takeAll)(ackQueue.takeBetween(1, _))
      .flatMap {
        case chunk if chunk.isEmpty => ZIO.none
        case chunk =>
          ZIO.attempt {
            val (ackIds, nackIds) = chunk.partitionMap {
              case (id, true)  => Left(id)
              case (id, false) => Right(id)
            }

            val req = StreamingPullRequest.newBuilder
              .addAllAckIds(ackIds.asJava)
              .addAllModifyDeadlineSeconds(nackIds.map(_ => Integer.valueOf(0)).asJava)
              .addAllModifyDeadlineAckIds(nackIds.asJava)
              .build()

            clientStream.send(req)
          }.uninterruptible
            .as(None)
            .catchAllCause { c =>
              // place back into the queue on failures
              ackQueue.offerAll(chunk).as(Some(c))
            }
      }

  private[pubsub] def makeStream(
    initBidiStream: ZStream[Any, Throwable, BidiStream[StreamingPullRequest, StreamingPullResponse]],
    ackQueue: Queue[(String, Boolean)],
    retrySchedule: Schedule[Any, Throwable, ?],
  ): ZStream[Any, Throwable, (GReceivedMessage, AckReply)] = (for {
    bidiStream <- initBidiStream
    stream = makeServerStream(bidiStream).map { message =>
               val ackReply = new AckReply {
                 override def ack(): UIO[Unit]  = ackQueue.offer((message.getAckId(), true)).uninterruptible.unit
                 override def nack(): UIO[Unit] = ackQueue.offer((message.getAckId(), false)).uninterruptible.unit
               }
               (message, ackReply)
             }
    ackStream = ZStream
                  .unfoldZIO(())(_ =>
                    processAckQueue(ackQueue, bidiStream, Some(1024)).flatMap {
                      case None    => ZIO.some(((), ()))
                      case Some(c) => ZIO.failCause(c)
                    }
                  )
    ackStreamFailed <- ZStream.fromZIO(Promise.make[Throwable, Nothing])
    _ <-
      ZStream.scopedWith { scope =>
        for {
          _ <-
            scope.addFinalizerExit { _ =>
              for {
                // cancel receiving stream before processing the rest of the queue
                _ <- ZIO.succeed(bidiStream.cancel())
                _ <- processAckQueue(ackQueue, bidiStream, None)
              } yield ()
            }
          _ <- ackStream.channel.drain.runIn(scope).catchAllCause(ackStreamFailed.failCause(_)).forkIn(scope)
        } yield ()
      }
    s <- stream.interruptWhen(ackStreamFailed)
  } yield s).retry(retrySchedule)

  private[pubsub] def initGrpcBidiStream(
    subscriber: GrpcSubscriberStub,
    subscriptionId: SubscriptionName,
    streamAckDeadlineSeconds: Int,
  ): ZStream[Any, Throwable, BidiStream[StreamingPullRequest, StreamingPullResponse]] = for {
    _ <- ZStream.logInfo(s"Initializing bidi stream...")
    bidiStream <- ZStream.fromZIO(ZIO.attempt {
                    val gBidiStream = subscriber.streamingPullCallable().call()
                    val req =
                      StreamingPullRequest.newBuilder
                        .setSubscription(subscriptionId.toString())
                        .setStreamAckDeadlineSeconds(streamAckDeadlineSeconds)
                        .build()
                    gBidiStream.send(req)
                    BidiStream.fromGrpcBidiStream(gBidiStream)
                  })
    _ <- ZStream.logInfo(s"Bidi stream initialized")
  } yield bidiStream

  private def shutdownSubscriber(subscriber: GrpcSubscriberStub) = (for {
    _ <- ZIO.logInfo(s"Shutting down subscriber...")
    awaitResult <- ZIO.attemptBlocking {
                     subscriber.shutdownNow()
                     subscriber.awaitTermination(30, TimeUnit.SECONDS)
                   }
    _ <- ZIO.logDebug(s"Subscriber terminated: $awaitResult")
  } yield ()).orDie

  def makeRawStream(
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    streamAckDeadlineSeconds: Int,
    retrySchedule: Schedule[Any, Throwable, ?],
  ): RIO[Scope, GoogleStream] = for {
    settings      <- settingsFromConfig(connection)
    subscriptionId = SubscriptionName.of(connection.project.name, subscriptionName)
    subscriber <-
      ZIO.acquireRelease(ZIO.attempt(GrpcSubscriberStub.create(settings)))(shutdownSubscriber)
    ackQueue <- ZIO.acquireRelease(Queue.unbounded[(String, Boolean)])(_.shutdown)
    stream =
      makeStream(
        initGrpcBidiStream(subscriber, subscriptionId, streamAckDeadlineSeconds),
        ackQueue,
        retrySchedule,
      ) @@ ZStreamAspect.annotated("subscription_name", subscriptionName)
  } yield stream
}

// interface for com.google.api.gax.rpc.BidiStream without implementations of com.google.api.gax.rpc.ServerStream to be enable to reproduce behaviour in tests
private[pubsub] trait BidiStream[A, B] extends ClientStream[A] with ServerStream[B]
private[pubsub] object BidiStream {
  def fromGrpcBidiStream[A, B](s: GBidiStream[A, B]): BidiStream[A, B] = new BidiStream[A, B] {
    override def send(request: A): Unit                 = s.send(request)
    override def closeSendWithError(t: Throwable): Unit = s.closeSendWithError(t)
    override def closeSend(): Unit                      = s.closeSend()
    override def isSendReady(): Boolean                 = s.isSendReady()
    override def iterator(): ju.Iterator[B]             = s.iterator()
    override def cancel(): Unit                         = s.cancel()
  }
}

// interface for com.google.api.gax.rpc.ServerStream
private[pubsub] trait ServerStream[T] extends java.lang.Iterable[T] {
  def cancel(): Unit
}
