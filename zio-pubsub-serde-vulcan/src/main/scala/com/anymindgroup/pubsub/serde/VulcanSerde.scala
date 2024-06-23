package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.model.Encoding
import com.anymindgroup.pubsub.sub.ReceivedMessage
import vulcan.Codec

import zio.{RIO, ZIO}

object VulcanSerde {
  def fromAvroCodec[T](codec: Codec[T], encoding: Encoding): Serde[Any, T] = new Serde[Any, T] {
    private implicit val c: Codec[T] = codec

    override def serialize(data: T): RIO[Any, Array[Byte]] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            Codec.toBinary(data)
          case Encoding.Json =>
            Codec.toJson(data).map(_.getBytes)
        })
        .mapError(_.throwable)

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            codec.schema.flatMap(sc => Codec.fromBinary(message.data, sc))
          case Encoding.Json =>
            codec.schema.flatMap(sc => Codec.fromJson(new String(message.data), sc))
        })
        .mapError(_.throwable)
  }
}
