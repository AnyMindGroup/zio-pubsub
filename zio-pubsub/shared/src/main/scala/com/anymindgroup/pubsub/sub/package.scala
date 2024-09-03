package com.anymindgroup.pubsub

package object sub {
  type Receipt[E] = (ReceivedMessage[E], AckReply)
  type RawReceipt = Receipt[Array[Byte]]
}
