package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.model.PubsubConnectionConfig
import com.google.cloud.pubsub.v1.{TopicAdminClient, TopicAdminSettings}

import zio.{RIO, RLayer, Scope, ZIO, ZLayer}

object TopicAdmin {
  def makeClient(connection: PubsubConnectionConfig): RIO[Scope, TopicAdminClient] =
    ZIO.acquireRelease(
      connection match {
        case config: PubsubConnectionConfig.Emulator =>
          for {
            (channelProvider, credentialsProvider) <- Emulator.createEmulatorSettings(config)
            s <- ZIO.attempt(
                   TopicAdminClient.create(
                     TopicAdminSettings
                       .newBuilder()
                       .setTransportChannelProvider(channelProvider)
                       .setCredentialsProvider(credentialsProvider)
                       .build()
                   )
                 )
          } yield s
        case _ => ZIO.attempt(TopicAdminClient.create())
      }
    ) { r =>
      ZIO.logDebug("Shutting down TopicAdminClient...") *> ZIO.succeed {
        r.shutdown()
        r.awaitTermination(30, TimeUnit.SECONDS)
      }
    }

  val layer: RLayer[PubsubConnectionConfig & Scope, TopicAdminClient] = ZLayer.fromZIO {
    for {
      connection <- ZIO.service[PubsubConnectionConfig]
      client     <- makeClient(connection)
    } yield client
  }
}
