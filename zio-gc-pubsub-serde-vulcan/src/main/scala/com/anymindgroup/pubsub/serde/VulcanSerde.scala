package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.model.Encoding
import com.anymindgroup.pubsub.sub.ReceivedMessage
import vulcan.Codec

import zio.{RIO, ZIO}

object VulcanSerde {
  def fromAvroCodec[T](codec: Codec[T], encoding: Encoding): Serde[Any, T] = new Serde[Any, T] {
    override def serialize(data: T): RIO[Any, Array[Byte]] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            Codec.toBinary(data)(codec)
          case Encoding.Json =>
            Codec.toJson(data)(codec).map(_.getBytes)
        })
        .mapError(_.throwable)

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            codec.schema.flatMap(sc => Codec.fromBinary(message.data, sc)(codec))
          case Encoding.Json =>
            codec.schema.flatMap(sc => Codec.fromJson(new String(message.data), sc)(codec))
        })
        .mapError(_.throwable)
  }
}
