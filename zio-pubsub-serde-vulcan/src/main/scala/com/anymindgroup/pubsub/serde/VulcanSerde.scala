package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.{Encoding, ReceivedMessage, Serde}
import vulcan.Codec

import zio.{Chunk, RIO, ZIO}

@deprecated("will be removed in future release in favor of zio-schema module")
object VulcanSerde {
  def fromAvroCodec[T](codec: Codec[T], encoding: Encoding): Serde[Any, T] = new Serde[Any, T] {
    private implicit val c: Codec[T] = codec

    override def serialize(data: T): RIO[Any, Chunk[Byte]] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            Codec.toBinary(data).map(Chunk.fromArray(_))
          case Encoding.Json =>
            Codec.toJson(data).map(j => Chunk.fromArray(j.getBytes))
        })
        .mapError(_.throwable)

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO
        .fromEither(encoding match {
          case Encoding.Binary =>
            codec.schema.flatMap(sc => Codec.fromBinary(message.data.toArray, sc))
          case Encoding.Json =>
            codec.schema.flatMap(sc => Codec.fromJson(String(message.data.toArray), sc))
        })
        .mapError(_.throwable)
  }
}
