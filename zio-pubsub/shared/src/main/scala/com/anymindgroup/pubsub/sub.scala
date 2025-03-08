package com.anymindgroup.pubsub.sub

type Receipt[E] = (ReceivedMessage[E], AckReply)
type RawReceipt = Receipt[Array[Byte]]
