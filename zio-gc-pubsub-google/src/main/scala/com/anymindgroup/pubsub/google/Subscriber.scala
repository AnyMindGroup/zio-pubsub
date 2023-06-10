package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.Deserializer
import com.anymindgroup.pubsub.sub.*

import zio.stream.ZStream
import zio.{Duration, RIO, Schedule, Scope, ZIO, durationInt}

object Subscriber {
  type StreamAckDeadlineSeconds = Int

  val defaultRetrySchedule: Schedule[Any, Throwable, ?] = Schedule.forever.addDelayZIO { l =>
    val delay = 1.second
    ZIO.logInfo(s"Stream recovery delayed by ${delay.toSeconds()} s attempt: ${l + 1}").as(delay)
  }

  val defaultStreamAckDeadlineSeconds: StreamAckDeadlineSeconds = 60

  def makeSubscriptionStream[R, E](
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    topic: Topic[R, E],
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, ZStream[R, Throwable, (ReceivedMessage[E], AckReply)]] =
    makeSubscriptionStreamWithDeserializer(
      connection,
      subscriptionName,
      topic.serde,
      streamAckDeadlineSeconds,
      retrySchedule,
    )

  def makeSubscriptionStreamWithDeserializer[R, E](
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    des: Deserializer[R, E],
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, ZStream[R, Throwable, (ReceivedMessage[E], AckReply)]] =
    makeRawStream(connection, subscriptionName, streamAckDeadlineSeconds, retrySchedule).map(
      _.via(Pipeline.deserializerPipeline(des))
    )

  private[pubsub] def makeTempUniqueRawSubscriptionStream(
    connection: PubsubConnectionConfig,
    topicName: String,
    subscriptionName: String,
    subscriptionFilter: Option[SubscriberFilter],
    maxTtl: Duration,
    enableOrdering: Boolean,
  ): RIO[Scope, (Subscription, ZStream[Any, Throwable, RawRecord])] = for {
    subscription <- SubscriptionAdmin.createTempSubscription(
                      connection = connection,
                      topicName = topicName,
                      subscriptionName = subscriptionName,
                      subscriptionFilter = subscriptionFilter,
                      maxTtl = maxTtl,
                      enableOrdering = enableOrdering,
                    )
    rawStream <- makeRawStream(connection, subscription.name, defaultStreamAckDeadlineSeconds, defaultRetrySchedule)
  } yield (subscription, rawStream)

  private[pubsub] def makeRawStream(
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?],
  ): RIO[Scope, RawStream] =
    StreamingPullSubscriber.makeRawStream(connection, subscriptionName, streamAckDeadlineSeconds, retrySchedule)
}
