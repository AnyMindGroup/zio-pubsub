package com.anymindgroup.pubsub.google

import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{FixedTransportChannelProvider, TransportChannelProvider}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import zio.{RIO, Scope, ZIO}

import java.util.concurrent.TimeUnit

sealed trait PubsubConnectionConfig {
  def project: PubsubConnectionConfig.GcpProject

}

object PubsubConnectionConfig {
  final case class Cloud(project: GcpProject)                  extends PubsubConnectionConfig
  final case class Emulator(project: GcpProject, host: String) extends PubsubConnectionConfig

  def createEmulatorSettings(config: Emulator): RIO[Scope, (TransportChannelProvider, CredentialsProvider)] = for {
    channel <- ZIO.acquireRelease(ZIO.attempt {
                 val channel: ManagedChannel = ManagedChannelBuilder.forTarget(config.host).usePlaintext().build
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

  final case class GcpProject(name: String) {
    override def toString: String = name
  }
}
