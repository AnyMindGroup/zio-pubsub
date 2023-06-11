package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.serde.Deserializer
import com.anymindgroup.pubsub.sub.*
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.{Duration, RIO, Schedule, Scope, ZIO, durationInt}

object Subscriber {
  type StreamAckDeadlineSeconds = Int

  val defaultRetrySchedule: Schedule[Any, Throwable, ?] = Schedule.forever.addDelayZIO { l =>
    val delay = 1.second
    ZIO.logInfo(s"Stream recovery delayed by ${delay.toSeconds()} s attempt: ${l + 1}").as(delay)
  }

  val defaultStreamAckDeadlineSeconds: StreamAckDeadlineSeconds = 60

  def makeStreamingPullSubscription[R, E](
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    des: Deserializer[R, E],
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, DecodedRStream[R, E]] =
    makeRawStreamingPullSubscription(connection, subscriptionName, streamAckDeadlineSeconds, retrySchedule).map(
      _.via(Pipeline.deserializerPipeline(des))
    )

  def makeRawStreamingPullSubscription(
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, RawStream] =
    makeGoogleStreamingPullSubscription(connection, subscriptionName, streamAckDeadlineSeconds, retrySchedule).map {
      _.map { case (gMessage, ackReply) =>
        (toRawReceivedMessage(gMessage), ackReply)
      }
    }

  def makeTempRawStreamingPullSubscription(
    connection: PubsubConnectionConfig,
    topicName: String,
    subscriptionName: String,
    subscriptionFilter: Option[SubscriberFilter],
    maxTtl: Duration,
    enableOrdering: Boolean,
  ): RIO[Scope, RawStream] = for {
    subscription <- SubscriptionAdmin.createTempSubscription(
                      connection = connection,
                      topicName = topicName,
                      subscriptionName = subscriptionName,
                      subscriptionFilter = subscriptionFilter,
                      maxTtl = maxTtl,
                      enableOrdering = enableOrdering,
                    )
    stream <- makeRawStreamingPullSubscription(
                connection,
                subscription.name,
                defaultStreamAckDeadlineSeconds,
                defaultRetrySchedule,
              )
  } yield stream

  private[pubsub] def toRawReceivedMessage(rm: GReceivedMessage): ReceivedMessage.Raw = {
    val msg = rm.getMessage
    val ts  = msg.getPublishTime()

    ReceivedMessage(
      data = msg.getData.toByteArray(),
      meta = ReceivedMessage.Metadata(
        messageId = MessageId(msg.getMessageId()),
        ackId = AckId(rm.getAckId()),
        orderingKey = OrderingKey.fromString(msg.getOrderingKey()),
        publishTime = Instant
          .ofEpochSecond(ts.getSeconds())
          .plusNanos(ts.getNanos().toLong),
        attributes = msg.getAttributesMap.asScala.toMap,
        deliveryAttempt = rm.getDeliveryAttempt(),
      ),
    )
  }

  private[pubsub] def makeGoogleStreamingPullSubscription(
    connection: PubsubConnectionConfig,
    subscriptionName: String,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?],
  ): RIO[Scope, GoogleStream] =
    StreamingPullSubscriber.makeRawStream(connection, subscriptionName, streamAckDeadlineSeconds, retrySchedule)
}
