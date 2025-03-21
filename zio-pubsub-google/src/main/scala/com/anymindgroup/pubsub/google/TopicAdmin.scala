package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.google.cloud.pubsub.v1.{TopicAdminClient, TopicAdminSettings}

import zio.{RIO, RLayer, Scope, ZIO, ZLayer}

@deprecated("will be removed from release 0.3. Use Google's Java clients directly instead for admin APIs.", since = "0.2.11")
object TopicAdmin {
  def makeClient(connection: PubsubConnectionConfig): RIO[Scope, TopicAdminClient] =
    ZIO.acquireRelease(
      connection match {
        case config: PubsubConnectionConfig.Emulator =>
          for {
            (channelProvider, credentialsProvider) <- PubsubConnectionConfig.createEmulatorSettings(config)
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
