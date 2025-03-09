package com.anymindgroup.pubsub

import zio.schema.codec.BinaryCodec
import zio.{Chunk, RIO, ZIO}

object BinaryCodecSerde {

  def fromBinaryCodec[T](codec: BinaryCodec[T]): Serde[Any, T] = new Serde[Any, T] {

    override def serialize(data: T): RIO[Any, Array[Byte]] = ZIO.succeed(codec.encode(data).toArray)

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO.fromEither(codec.decode(Chunk.from(message.data)))
  }
}
