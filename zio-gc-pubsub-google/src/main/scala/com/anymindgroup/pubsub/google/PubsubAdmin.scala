package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.model.*
import com.anymindgroup.pubsub.sub.*
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.pubsub.v1.{TopicName, Encoding as GEncoding, SchemaSettings as GSchemaSettings, Topic as GTopic}
import zio.{RIO, Task, ZIO}

object PubsubAdmin {

  def setup(topics: Seq[Topic[?, ?]], subscriptions: Seq[Subscription]): RIO[PubsubConnectionConfig, Unit] =
    ZIO.serviceWithZIO[PubsubConnectionConfig](setup(_, topics, subscriptions))

  def setup(
    connection: PubsubConnectionConfig,
    topics: Seq[Topic[?, ?]],
    subscriptions: Seq[Subscription],
  ): Task[Unit] =
    ZIO.scoped(
      for {
        topicAdmin        <- TopicAdmin.makeClient(connection)
        _                 <- setupTopicsWithSchema(connection, topicAdmin, topics)
        subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
        _ <- ZIO.foreachDiscard(subscriptions) { s =>
               SubscriptionAdmin.createSubscriptionIfNotExists(connection, subscriptionAdmin, s, topicAdmin)
             }
      } yield ()
    )

  private def createSchema[T](
    connection: PubsubConnectionConfig,
    topicName: TopicName,
    schemaIn: SchemaSettings,
  ): ZIO[Any, Throwable, Option[GTopic]] =
    for {
      schemaSettings <- ZIO.foreach(schemaIn.schema)(sch =>
                          for {
                            schema <- PubSubSchemaRegistryAdmin.createIfNotExists(connection, sch)
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
    connection: PubsubConnectionConfig,
    taClient: TopicAdminClient,
    topics: Seq[Topic[?, ?]],
  ): RIO[Any, Unit] = {
    val list: Seq[(TopicName, SchemaSettings)] =
      topics.map(t => (TopicName.of(connection.project.name, t.name), t.schemaSetting))

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
