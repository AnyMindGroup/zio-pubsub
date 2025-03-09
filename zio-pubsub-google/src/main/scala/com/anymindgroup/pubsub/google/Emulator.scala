package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.PubsubConnectionConfig
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{FixedTransportChannelProvider, TransportChannelProvider}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import zio.{RIO, Scope, ZIO}

object Emulator {
  def createEmulatorSettings(
    config: PubsubConnectionConfig.Emulator
  ): RIO[Scope, (TransportChannelProvider, CredentialsProvider)] = for {
    channel <- ZIO.acquireRelease(ZIO.attempt {
                 val channel: ManagedChannel =
                   ManagedChannelBuilder.forTarget(s"${config.host}:${config.port}").usePlaintext().build
                 GrpcTransportChannel.create(channel)
               }) { channel =>
                 (for {
                   _ <- ZIO.logDebug(s"Shutting down channel ${channel.getTransportName}...")
                   terminated <- ZIO.attempt {
                                   channel.shutdownNow()
                                   channel.awaitTermination(30, TimeUnit.SECONDS)
                                 }
                   _ <- ZIO.logDebug(s"Channel ${channel.getTransportName} terminated: $terminated")
                 } yield ()).orDie
               }
    channelProvider    <- ZIO.attempt(FixedTransportChannelProvider.create(channel))
    credentialsProvider = NoCredentialsProvider.create
  } yield (channelProvider, credentialsProvider)
}
