package com.anymindgroup.pubsub

import com.anymindgroup.pubsub.sub.AckReply
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.stream.{ZPipeline, ZStream}

package object google {
  type RawRecord               = (GReceivedMessage, AckReply)
  type DecodedRecord[E]        = (E, AckReply)
  type DecoderResult[E]        = Either[Throwable, (E, AckReply)]
  type RawStream               = ZStream[Any, Throwable, RawRecord]
  type DecodedStream[E]        = ZStream[Any, Throwable, DecodedRecord[E]]
  type TaskPipeline[R, A, B]   = ZPipeline[R, Throwable, A, B]
  type DecodeResultPipeline[E] = TaskPipeline[Any, RawRecord, DecoderResult[E]]
  type DecodedPipeline[E]      = TaskPipeline[Any, RawRecord, DecodedRecord[E]]
  type DecodedRPipeline[R, E]  = TaskPipeline[R, RawRecord, DecodedRecord[E]]
}
