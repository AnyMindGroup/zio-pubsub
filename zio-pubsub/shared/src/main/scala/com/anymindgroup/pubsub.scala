package com.anymindgroup.pubsub

type Receipt[E] = (ReceivedMessage[E], AckReply)
type RawReceipt = Receipt[Array[Byte]]
