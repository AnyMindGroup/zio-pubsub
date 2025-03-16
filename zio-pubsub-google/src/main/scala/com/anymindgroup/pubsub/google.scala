package com.anymindgroup.pubsub
package google

import java.util.concurrent.TimeUnit

import com.google.api.gax.core.{BackgroundResource, CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{ClientSettings, FixedTransportChannelProvider, StubSettings, TransportChannelProvider}
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import zio.stream.ZStream
import zio.{RIO, Scope, Task, ZIO}

private[google] type GoogleReceipt = (GReceivedMessage, AckReply)
private[google] type GoogleStream  = ZStream[Any, Throwable, GoogleReceipt]

private def acquireBackgroundReource[R <: BackgroundResource](
  acquire: => Task[R]
) = ZIO.acquireRelease(acquire)(resource =>
  ZIO.attempt {
    resource.shutdown()
    resource.awaitTermination(30, TimeUnit.SECONDS)
  }.ignore
)

def createEmulatorSettings(
  config: PubsubConnectionConfig.Emulator
): RIO[Scope, (TransportChannelProvider, CredentialsProvider)] = for {
  channel <- acquireBackgroundReource(ZIO.attempt {
               val channel: ManagedChannel =
                 ManagedChannelBuilder.forTarget(s"${config.host}:${config.port}").usePlaintext().build()
               GrpcTransportChannel.create(channel)
             })
  channelProvider    <- ZIO.attempt(FixedTransportChannelProvider.create(channel))
  credentialsProvider = NoCredentialsProvider.create
} yield (channelProvider, credentialsProvider)

def createClient[
  Settings <: ClientSettings[Settings],
  SettingsBuilder <: ClientSettings.Builder[Settings, SettingsBuilder],
  Client <: BackgroundResource,
](
  connection: PubsubConnectionConfig,
  builder: => SettingsBuilder,
  create: Settings => Client,
): ZIO[Scope, Throwable, Client] =
  connection match
    case PubsubConnectionConfig.Cloud =>
      acquireBackgroundReource(ZIO.attempt(create(builder.build().asInstanceOf[Settings])))
    case conf: PubsubConnectionConfig.Emulator =>
      for {
        (channel, credentials) <- createEmulatorSettings(conf)
        client <- acquireBackgroundReource(
                    ZIO.attempt(
                      create(
                        builder
                          .setCredentialsProvider(credentials)
                          .setTransportChannelProvider(channel)
                          .build()
                          .asInstanceOf[Settings]
                      )
                    )
                  )
      } yield client

private def createStub[
  Settings <: StubSettings[Settings],
  SettingsBuilder <: StubSettings.Builder[Settings, SettingsBuilder],
  Client <: BackgroundResource,
](
  connection: PubsubConnectionConfig,
  builder: => SettingsBuilder,
  create: Settings => Client,
): ZIO[Scope, Throwable, Client] =
  connection match
    case PubsubConnectionConfig.Cloud =>
      acquireBackgroundReource(ZIO.attempt(create(builder.build().asInstanceOf[Settings])))
    case conf: PubsubConnectionConfig.Emulator =>
      for {
        (channel, credentials) <- createEmulatorSettings(conf)
        client <- acquireBackgroundReource(
                    ZIO.attempt(
                      create(
                        builder
                          .setCredentialsProvider(credentials)
                          .setTransportChannelProvider(channel)
                          .build()
                          .asInstanceOf[Settings]
                      )
                    )
                  )
      } yield client
