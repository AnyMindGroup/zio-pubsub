# Subscriber

## Basic subscription
A basic subscription stream that is going to deserialize message data as integers can be defined as follows:
```scala
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

val subStream: ZStream[Subscriber, Throwable, (ReceivedMessage[Int], AckReply)] =
  Subscriber.subscribe(subscriptionName = "basic_example", des = Serde.int)
```

In the next step one can define a function that is going to process a message 
and reply depending on the result:
```scala
import zio.Console.printLine

// ack on successful process
// nack and log the error cause on failures
def process(message: ReceivedMessage[Int], reply: AckReply) =
  printLine(s"Processing of $message that can fail...").exit.flatMap {
    case Exit.Success(_)     => reply.ack()
    case Exit.Failure(cause) => reply.nack() *> ZIO.logErrorCause(s"Failure log", cause)
  }  
```

Processing can then be executed according to the needs.  
E.g. to process all messages in sequence:
```scala
subStream.mapZIO(process)
```

Process up to 16 messages concurrently:
```scala
subStream.mapZIOPar(16)(process)
```

Group by ordering key and process the groups in parallel
while each group item is processed sequentially:  

```scala
subStream.mapZIOParByKey(
    keyBy = (m, _) => m.meta.orderingKey.getOrElse(m.meta.messageId).hashCode(),
    buffer = 16,
)(process)
```

## Subscription with custom deserializer

A subscription with a custom deserializer can be defined as follows:

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

// deserialize an array of bytes to an UTF8 string
// the output type of the deserializer is also represented by the type: Deserializer[?, String]
val utf8StringDes: Deserializer[Any, String] = (message: ReceivedMessage[Array[Byte]]) =>
  ZIO.attempt(String(message.data, java.nio.charset.StandardCharsets.UTF_8))

// deserializer output will be reflected in the message type that is emitted by the stream: ReceivedMessage[String]
val subStream: ZStream[Subscriber, Throwable, (ReceivedMessage[String], AckReply)] =
  Subscriber.subscribe(subscriptionName = "basic_example", des = utf8StringDes)
```

## Subscription without deserializer

It's also possible to create a stream without a deserializer which is going 
to emit `ReceivedMessage[Array[Byte]]` containing data as array of bytes.

```scala
import com.anymindgroup.pubsub.*, zio.stream.ZStream

val subStream: ZStream[Subscriber, Throwable, (ReceivedMessage[Array[Byte]], AckReply)] =
  Subscriber.subscribeRaw(subscriptionName = "basic_example")
```