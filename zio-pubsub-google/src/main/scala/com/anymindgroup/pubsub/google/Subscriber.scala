package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.sub.*
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.stream.ZStream
import zio.{Duration, RIO, Schedule, Scope, ZIO, ZLayer, durationInt}

object Subscriber {
  type StreamAckDeadlineSeconds = Int

  val defaultRetrySchedule: Schedule[Any, Throwable, ?] = Schedule.forever.addDelayZIO { l =>
    val delay = 1.second
    ZIO.logInfo(s"Stream recovery delayed by ${delay.toSeconds()} s attempt: ${l + 1}").as(delay)
  }

  val defaultStreamAckDeadlineSeconds: StreamAckDeadlineSeconds = 60

  def makeStreamingPullSubscriber(
    connection: PubsubConnectionConfig,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, Subscriber] = ZIO.serviceWith[Scope](scope =>
    new Subscriber {
      override def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt] =
        ZStream
          .fromZIO(
            makeRawStreamingPullSubscription(
              connection = connection,
              subscriptionName = subscriptionName,
              streamAckDeadlineSeconds = streamAckDeadlineSeconds,
              retrySchedule = retrySchedule,
            ).provide(ZLayer.succeed(scope))
          )
          .flatten
    }
  )

  private[pubsub] def makeRawStreamingPullSubscription(
    subscriptionName: SubscriptionName,
    connection: PubsubConnectionConfig,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?] = defaultRetrySchedule,
  ): RIO[Scope, ZStream[Any, Throwable, RawReceipt]] =
    makeGoogleStreamingPullSubscription(
      connection = connection,
      subscriptionName = subscriptionName,
      streamAckDeadlineSeconds = streamAckDeadlineSeconds,
      retrySchedule = retrySchedule,
    ).map {
      _.map { case (gMessage, ackReply) =>
        (toRawReceivedMessage(gMessage), ackReply)
      }
    }

  private[pubsub] def makeTempRawStreamingPullSubscription(
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    subscriptionName: SubscriptionName,
    subscriptionFilter: Option[SubscriberFilter],
    maxTtl: Duration,
    enableOrdering: Boolean,
  ): RIO[Scope, ZStream[Any, Throwable, RawReceipt]] = for {
    subscription <- SubscriptionAdmin.createTempSubscription(
                      connection = connection,
                      topicName = topicName,
                      subscriptionName = subscriptionName,
                      subscriptionFilter = subscriptionFilter,
                      maxTtl = maxTtl,
                      enableOrdering = enableOrdering,
                    )
    stream <- makeRawStreamingPullSubscription(
                connection = connection,
                subscriptionName = subscription.name,
                streamAckDeadlineSeconds = defaultStreamAckDeadlineSeconds,
                retrySchedule = defaultRetrySchedule,
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
    subscriptionName: SubscriptionName,
    streamAckDeadlineSeconds: StreamAckDeadlineSeconds,
    retrySchedule: Schedule[Any, Throwable, ?],
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GoogleStream] =
    StreamingPullSubscriber.makeRawStream(
      connection = connection,
      subscriptionName = subscriptionName,
      streamAckDeadlineSeconds = streamAckDeadlineSeconds,
      retrySchedule = retrySchedule,
    )
}
