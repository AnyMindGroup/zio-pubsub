package com.anymindgroup.pubsub

import zio.stream.{ZPipeline, ZStream}

package object sub {
  type RawReceipt              = (ReceivedMessage.Raw, AckReply)
  type RawStream               = ZStream[Any, Throwable, RawReceipt]
  type RawRStream[R]           = ZStream[R, Throwable, RawReceipt]
  type DecodedReceipt[E]       = (ReceivedMessage[E], AckReply)
  type DecodedReceiptResult[E] = Either[Throwable, (ReceivedMessage[E], AckReply)]
  type DecodedStream[E]        = ZStream[Any, Throwable, DecodedReceipt[E]]
  type DecodedRStream[R, E]    = ZStream[R, Throwable, DecodedReceipt[E]]
  type TaskPipeline[R, A, B]   = ZPipeline[R, Throwable, A, B]
  type DecodeResultPipeline[E] = TaskPipeline[Any, RawReceipt, DecodedReceiptResult[E]]
  type DecodedPipeline[E]      = TaskPipeline[Any, RawReceipt, DecodedReceipt[E]]
  type DecodedRPipeline[R, E]  = TaskPipeline[R, RawReceipt, DecodedReceipt[E]]
}
