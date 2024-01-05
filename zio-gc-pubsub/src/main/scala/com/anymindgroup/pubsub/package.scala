package com.anymindgroup

package object pubsub {
  type MessageId   = model.MessageId
  type Topic[R, T] = model.Topic[R, T]
  type Encoding    = model.Encoding
  type OrderingKey = model.OrderingKey

  type SchemaSettings = model.SchemaSettings
  type SchemaRegistry = model.SchemaRegistry
  type SchemaType     = model.SchemaType

  type Publisher[R, T]   = pub.Publisher[R, T]
  type PublishMessage[T] = pub.PublishMessage[T]

  type Subscriber         = sub.Subscriber
  type Subscription       = sub.Subscription
  type SubscriberFilter   = sub.SubscriberFilter
  type ReceivedMessage[T] = sub.ReceivedMessage[T]
  type AckReply           = sub.AckReply
  type AckId              = sub.AckId

  type Deserializer[R, T] = serde.Deserializer[R, T]
  type Serializer[R, T]   = serde.Serializer[R, T]
  type Serde[R, T]        = serde.Serde[R, T]

  object Serde extends serde.Serdes
}
