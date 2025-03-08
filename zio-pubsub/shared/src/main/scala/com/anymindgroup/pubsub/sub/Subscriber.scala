package com.anymindgroup.pubsub.sub

import com.anymindgroup.pubsub.model.SubscriptionName
import com.anymindgroup.pubsub.serde.Deserializer

import zio.stream.ZStream

trait Subscriber {
  def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt]

  final def subscribe[R, E](
    subscriptionName: SubscriptionName,
    deserializer: Deserializer[R, E],
  ): ZStream[R, Throwable, Receipt[E]] =
    subscribeRaw(subscriptionName).via(Pipeline.deserializerPipeline(deserializer))
}

object Subscriber {
  def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Subscriber, Throwable, RawReceipt] =
    ZStream.serviceWithStream[Subscriber](_.subscribeRaw(subscriptionName))

  def subscribe[R, E](
    subscriptionName: SubscriptionName,
    deserializer: Deserializer[R, E],
  ): ZStream[R & Subscriber, Throwable, Receipt[E]] =
    ZStream.serviceWithStream[Subscriber](_.subscribe(subscriptionName, deserializer))

}
