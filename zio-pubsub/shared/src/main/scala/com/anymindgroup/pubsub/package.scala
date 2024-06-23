package com.anymindgroup

package object pubsub {
  type MessageId   = model.MessageId
  type Topic[R, T] = model.Topic[R, T]
  val Topic = model.Topic

  type Encoding = model.Encoding
  val Encoding = model.Encoding

  type OrderingKey = model.OrderingKey

  type SchemaSettings = model.SchemaSettings
  val SchemaSettings = model.SchemaSettings

  type SchemaRegistry = model.SchemaRegistry
  type SchemaType     = model.SchemaType

  type Publisher[R, T] = pub.Publisher[R, T]
  val Publisher = pub.Publisher

  type PublishMessage[T] = pub.PublishMessage[T]
  val PublishMessage = pub.PublishMessage

  type Subscriber = sub.Subscriber
  val Subscriber = sub.Subscriber
  type Subscription = sub.Subscription
  val Subscription = sub.Subscription

  type SubscriberFilter   = sub.SubscriberFilter
  type ReceivedMessage[T] = sub.ReceivedMessage[T]
  type AckReply           = sub.AckReply
  type AckId              = sub.AckId

  type Deserializer[R, T] = serde.Deserializer[R, T]
  type Serializer[R, T]   = serde.Serializer[R, T]
  type Serde[R, T]        = serde.Serde[R, T]

  val Serde = serde.Serde

  type DeadLettersSettings = sub.DeadLettersSettings
  val DeadLettersSettings = sub.DeadLettersSettings
}
