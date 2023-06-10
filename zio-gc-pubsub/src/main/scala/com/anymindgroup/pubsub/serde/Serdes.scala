package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.sub.ReceivedMessage

import zio.{RIO, ZIO}

private[pubsub] trait Serdes {
  val byteArray: Serde[Any, Array[Byte]] =
    new Serde[Any, Array[Byte]] {
      override final def serialize(value: Array[Byte]): RIO[Any, Array[Byte]] = ZIO.succeed(value)
      override final def deserialize(message: ReceivedMessage.Raw): RIO[Any, Array[Byte]] =
        ZIO.succeed(message.data)
    }
}
