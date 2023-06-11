package com.anymindgroup.pubsub

import com.anymindgroup.pubsub.sub.AckReply
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.stream.ZStream

package object google {
  private[pubsub] type GoogleReceipt = (GReceivedMessage, AckReply)
  private[pubsub] type GoogleStream  = ZStream[Any, Throwable, (GReceivedMessage, AckReply)]
}
