package com.anymindgroup.pubsub.sub

import com.anymindgroup.pubsub.serde.Deserializer

import zio.stream.ZStream

trait Subscriber {
  def subscribeRaw(subscriptionName: String): ZStream[Any, Throwable, RawReceipt]

  final def subscribe[R, E](
    subscriptionName: String,
    des: Deserializer[R, E],
  ): ZStream[R, Throwable, Receipt[E]] =
    subscribeRaw(subscriptionName).via(Pipeline.deserializerPipeline(des))
}

object Subscriber {
  def subscribeRaw(subscriptionName: String): ZStream[Subscriber, Throwable, RawReceipt] =
    ZStream.serviceWithStream[Subscriber](_.subscribeRaw(subscriptionName))

  def subscribe[R, E](
    subscriptionName: String,
    des: Deserializer[R, E],
  ): ZStream[R & Subscriber, Throwable, Receipt[E]] =
    ZStream.serviceWithStream[Subscriber](_.subscribe(subscriptionName, des))

}
