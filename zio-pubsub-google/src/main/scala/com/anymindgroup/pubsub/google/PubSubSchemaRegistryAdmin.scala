package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.model.{PubsubConnectionConfig, SchemaRegistry, SchemaType}
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.pubsub.v1.{SchemaServiceClient, SchemaServiceSettings}
import com.google.pubsub.v1.{ProjectName, Schema as GSchema, SchemaName}

import zio.{RIO, Scope, Task, ZIO}

object PubSubSchemaRegistryAdmin {

  private[pubsub] def makeClient(connection: PubsubConnectionConfig): RIO[Scope, SchemaServiceClient] =
    ZIO.acquireRelease(
      connection match {
        case config: PubsubConnectionConfig.Emulator =>
          for {
            (channelProvider, credentialsProvider) <- Emulator.createEmulatorSettings(config)
            s <- ZIO.attempt(
                   SchemaServiceClient.create(
                     SchemaServiceSettings
                       .newBuilder()
                       .setTransportChannelProvider(channelProvider)
                       .setCredentialsProvider(credentialsProvider)
                       .build()
                   )
                 )
          } yield s
        case _ => ZIO.attempt(SchemaServiceClient.create())
      }
    ) { r =>
      ZIO.logDebug("Shutting down SchemaServiceClient...") *> ZIO.succeed {
        r.shutdown()
        r.awaitTermination(30, TimeUnit.SECONDS)
      }
    }

  def createIfNotExists(
    schemaRegistry: SchemaRegistry,
    schemaClient: Option[SchemaServiceClient] = None,
    connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
  ): Task[GSchema] =
    ZIO.scoped:
      for {
        client <- ZIO.fromOption(schemaClient).orElse(makeClient(connection))
        result <- createIfNotExists(client, schemaRegistry)
      } yield result

  private def mapSchemaType(domainType: SchemaType): GSchema.Type =
    domainType match {
      case SchemaType.ProtocolBuffer => GSchema.Type.PROTOCOL_BUFFER
      case SchemaType.Avro           => GSchema.Type.AVRO
    }

  private def createIfNotExists(
    schemaClient: SchemaServiceClient,
    schemaRegistry: SchemaRegistry,
  ): Task[GSchema] =
    for {
      schemaName <- ZIO.succeed(SchemaName.format(schemaRegistry.name.projectId, schemaRegistry.name.schemaId))
      definition <- schemaRegistry.definition
      schema <- ZIO.attempt(schemaClient.getSchema(schemaName)).catchSome { case _: NotFoundException =>
                  for {
                    _ <- ZIO.logInfo(s"Creating avro schema ${schemaName}")
                    schema <- ZIO.attempt(
                                schemaClient.createSchema(
                                  ProjectName.format(schemaRegistry.name.projectId),
                                  GSchema
                                    .newBuilder()
                                    .setName(schemaName)
                                    .setType(mapSchemaType(schemaRegistry.schemaType))
                                    .setDefinition(definition)
                                    .build(),
                                  schemaRegistry.name.schemaId,
                                )
                              )
                    _ <- ZIO.logInfo(s"${schemaRegistry.schemaType} schema ${schemaName} created")
                  } yield schema
                }
    } yield schema

}
