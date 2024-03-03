package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.sub.{SubscriberFilter, Subscription}
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.protobuf.Duration as ProtoDuration
import com.google.pubsub.v1.{
  DeadLetterPolicy,
  ExpirationPolicy,
  ProjectTopicName,
  Subscription as GSubscription,
  SubscriptionName,
  TopicName,
}

import zio.{Duration, RIO, RLayer, Scope, ZIO, ZLayer}

object SubscriptionAdmin {
  def makeClient(connection: PubsubConnectionConfig): RIO[Scope, SubscriptionAdminClient] =
    ZIO.acquireRelease(
      connection match {
        case config: PubsubConnectionConfig.Emulator =>
          for {
            (channelProvider, credentialsProvider) <- PubsubConnectionConfig.createEmulatorSettings(config)
            s <- ZIO.attempt(
                   SubscriptionAdminClient.create(
                     SubscriptionAdminSettings
                       .newBuilder()
                       .setTransportChannelProvider(channelProvider)
                       .setCredentialsProvider(credentialsProvider)
                       .build()
                   )
                 )
          } yield s
        case _ => ZIO.attempt(SubscriptionAdminClient.create())
      }
    ) { r =>
      ZIO.logDebug("Shutting down SubscriptionAdminClient...") *> ZIO.succeed {
        r.shutdown()
        r.awaitTermination(30, TimeUnit.SECONDS)
      }
    }

  val layer: RLayer[PubsubConnectionConfig & Scope, SubscriptionAdminClient] = ZLayer.fromZIO {
    for {
      connection <- ZIO.service[PubsubConnectionConfig]
      client     <- makeClient(connection)
    } yield client
  }

  def createSubscriptionIfNotExists(
    connection: PubsubConnectionConfig,
    subscription: Subscription,
  ): RIO[Scope, Unit] =
    for {
      subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
      _                 <- createSubscriptionIfNotExists(connection, subscriptionAdmin, subscription)
    } yield ()

  private def checkDeadLettersTopicExists(
    connection: PubsubConnectionConfig,
    subscription: Subscription,
  ): RIO[Scope, Unit] = subscription.deadLettersSettings
    .map(s =>
      TopicAdmin
        .makeClient(connection)
        .flatMap(admin =>
          ZIO.attempt(
            admin.getTopic(
              ProjectTopicName
                .of(connection.project.name, s.deadLetterTopicName)
                .toString
            )
          )
        )
        .as(())
    )
    .getOrElse(ZIO.unit)
    .tapError(_ => ZIO.logError(s"Dead letter topic for subscription ${subscription.name} not found!"))

  def createSubscriptionIfNotExists(
    connection: PubsubConnectionConfig,
    subscriptionAdmin: SubscriptionAdminClient,
    subscription: Subscription,
  ): RIO[Scope, Unit] =
    for {
      _ <- checkDeadLettersTopicExists(connection, subscription)
      gSubscription <- ZIO.attempt {
                         val topicId        = TopicName.of(connection.project.name, subscription.topicName)
                         val subscriptionId = SubscriptionName.of(connection.project.name, subscription.name)
                         val expirationPolicy = subscription.expiration.map { t =>
                           ExpirationPolicy
                             .newBuilder()
                             .setTtl(ProtoDuration.newBuilder().setSeconds(t.getSeconds()))
                             .build()
                         }
                         val deadLetterPolicy: Option[DeadLetterPolicy] =
                           subscription.deadLettersSettings.map(s =>
                             DeadLetterPolicy
                               .newBuilder()
                               .setDeadLetterTopic(
                                 ProjectTopicName.of(connection.project.name, s.deadLetterTopicName).toString
                               )
                               .setMaxDeliveryAttempts(s.maxRetryNum)
                               .build()
                           )
                         val subscriptionBuilder = GSubscription
                           .newBuilder()
                           .setTopic(topicId.toString)
                           .setName(subscriptionId.toString)
                           .setEnableMessageOrdering(subscription.enableOrdering)

                         expirationPolicy.foreach(subscriptionBuilder.setExpirationPolicy)
                         deadLetterPolicy.foreach(subscriptionBuilder.setDeadLetterPolicy)
                         subscription.filter.foreach(s => subscriptionBuilder.setFilter(s.value))

                         subscriptionBuilder.build()
                       }
      _ <- ZIO.attempt(subscriptionAdmin.createSubscription(gSubscription)).catchSome {
             case _: AlreadyExistsException => ZIO.unit
           }
    } yield ()

  def createTempSubscription(
    connection: PubsubConnectionConfig,
    topicName: String,
    subscriptionName: String,
    subscriptionFilter: Option[SubscriberFilter],
    maxTtl: Duration,
    enableOrdering: Boolean,
  ): RIO[Scope, Subscription] = for {
    subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
    subscriptionId     = SubscriptionName.of(connection.project.name, subscriptionName)
    topicId            = TopicName.of(connection.project.name, topicName)

    expirationPolicy =
      ExpirationPolicy.newBuilder().setTtl(ProtoDuration.newBuilder().setSeconds(maxTtl.getSeconds())).build()
    subscriptionBuilder = GSubscription
                            .newBuilder()
                            .setTopic(topicId.toString)
                            .setName(subscriptionId.toString())
                            .setExpirationPolicy(expirationPolicy)
                            .setEnableMessageOrdering(enableOrdering)
    subscription = subscriptionFilter.fold(subscriptionBuilder)(s => subscriptionBuilder.setFilter(s.value)).build()

    _ <- ZIO.acquireRelease(ZIO.attempt(subscriptionAdmin.createSubscription(subscription)))(s =>
           ZIO.attempt(subscriptionAdmin.deleteSubscription(s.getName())).orDie
         )
  } yield Subscription(
    topicName = topicName,
    name = subscriptionId.getSubscription(),
    filter = subscriptionFilter,
    enableOrdering = enableOrdering,
    expiration = Some(maxTtl),
    deadLettersSettings = None,
  )
}
