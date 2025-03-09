package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.*
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.pubsub.v1.{
  Encoding as GEncoding,
  SchemaSettings as GSchemaSettings,
  Topic as GTopic,
  TopicName as GTopicName,
}

import zio.{RIO, Task, ZIO}

object PubsubAdmin {

  def setup(
    topics: Seq[Topic[?, ?]],
    subscriptions: Seq[Subscription],
    connection: PubsubConnectionConfig,
  ): Task[Unit] =
    ZIO.scoped(
      for {
        topicAdmin        <- TopicAdmin.makeClient(connection)
        _                 <- setupTopicsWithSchema(topicAdmin, topics, connection)
        subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
        _ <- ZIO.foreachDiscard(subscriptions) { s =>
               SubscriptionAdmin.createOrUpdateWithClient(
                 connection = connection,
                 subscriptionAdmin = subscriptionAdmin,
                 subscription = s,
               )
             }
      } yield ()
    )

  private def createSchema[T](
    connection: PubsubConnectionConfig,
    topicName: GTopicName,
    schemaIn: SchemaSettings,
  ): ZIO[Any, Throwable, Option[GTopic]] =
    for {
      schemaSettings <- ZIO.foreach(schemaIn.schema)(sch =>
                          for {
                            schema <- PubSubSchemaRegistryAdmin.createIfNotExists(sch, None, connection)
                            enc = schemaIn.encoding match {
                                    case Encoding.Json   => GEncoding.JSON
                                    case Encoding.Binary => GEncoding.BINARY
                                  }
                            schemaSettings = GSchemaSettings.newBuilder().setEncoding(enc).setSchema(schema.getName)
                          } yield schemaSettings
                        )
      topic = schemaSettings.map(setting =>
                GTopic
                  .newBuilder()
                  .setName(topicName.toString)
                  .setSchemaSettings(setting)
                  .build()
              )
    } yield topic

  private def setupTopicsWithSchema(
    taClient: TopicAdminClient,
    topics: Seq[Topic[?, ?]],
    connection: PubsubConnectionConfig,
  ): RIO[Any, Unit] = {
    val list: Seq[(GTopicName, SchemaSettings)] =
      topics.map(t => (GTopicName.of(t.name.projectId, t.name.topic), t.schemaSetting))

    ZIO
      .foreach(list) { case (topicName, schemaSettings) =>
        createSchema(
          connection = connection,
          topicName = topicName,
          schemaIn = schemaSettings,
        ).someOrElse(GTopic.newBuilder().setName(topicName.toString).build())
      }
      .flatMap(
        ZIO.foreachDiscard(_)(topic =>
          ZIO.attempt(taClient.createTopic(topic)).catchSome { case _: AlreadyExistsException =>
            ZIO.unit
          }
        )
      )
  }

}
