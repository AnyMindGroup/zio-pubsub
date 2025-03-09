package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.model.{PubsubConnectionConfig, SubscriptionName, TopicName}
import com.anymindgroup.pubsub.sub.{DeadLettersSettings, SubscriberFilter, Subscription}
import com.google.api.gax.rpc.{AlreadyExistsException, NotFoundException}
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.protobuf.{Duration as ProtoDuration, FieldMask}
import com.google.pubsub.v1.{
  DeadLetterPolicy as GDeadLetterPolicy,
  ExpirationPolicy as GExpirationPolicy,
  Subscription as GSubscription,
  SubscriptionName as GSubscriptionName,
  TopicName as GTopicName,
  UpdateSubscriptionRequest,
}

import zio.{Duration, RIO, RLayer, Scope, ZIO, ZLayer, durationLong}

object SubscriptionAdmin {
  def makeClient(
    connection: PubsubConnectionConfig
  ): RIO[Scope, SubscriptionAdminClient] =
    ZIO.acquireRelease(
      connection match {
        case config: PubsubConnectionConfig.Emulator =>
          for {
            (channelProvider, credentialsProvider) <- Emulator.createEmulatorSettings(config)
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

  def createOrUpdate(
    subscription: Subscription,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, Unit] =
    for {
      subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
      _                 <- createOrUpdateWithClient(subscriptionAdmin, subscription, connection)
    } yield ()

  private def createDeadLettersTopicIfNeeded(
    connection: PubsubConnectionConfig,
    subscription: Subscription,
  ): RIO[Scope, Unit] = subscription.deadLettersSettings
    .map(s =>
      TopicAdmin
        .makeClient(connection)
        .flatMap { admin =>
          val tn = GTopicName.format(s.deadLetterTopicName.projectId, s.deadLetterTopicName.topic)
          ZIO
            .attempt(admin.getTopic(tn))
            .unit
            .catchSome { case _: NotFoundException =>
              for {
                _ <- ZIO.logInfo(s"Dead letter topic for subscription ${subscription.name} not found! Creating...")
                _ <-
                  ZIO.attempt(admin.createTopic(tn))
                _ <- ZIO.logInfo(
                       s"Created dead letter topic for subscription ${subscription.name}: ${s.deadLetterTopicName}"
                     )
              } yield ()
            }
        }
    )
    .getOrElse(ZIO.unit)

  private def buildGSubscription(subscription: Subscription): GSubscription = {
    val topicId        = GTopicName.of(subscription.topicName.projectId, subscription.topicName.topic)
    val subscriptionId = GSubscriptionName.of(subscription.name.projectId, subscription.name.subscription)
    val expirationPolicy = subscription.expiration.map { t =>
      GExpirationPolicy
        .newBuilder()
        .setTtl(ProtoDuration.newBuilder().setSeconds(t.getSeconds()))
        .build()
    }
    val deadLetterPolicy: Option[GDeadLetterPolicy] =
      subscription.deadLettersSettings.map(s =>
        GDeadLetterPolicy
          .newBuilder()
          .setDeadLetterTopic(s.deadLetterTopicName.fullName)
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

  def fetchCurrentSubscription(
    subscriptionAdmin: SubscriptionAdminClient,
    subscriptionName: SubscriptionName,
  ): ZIO[Any, Throwable, Option[Subscription]] =
    ZIO
      .attempt(
        subscriptionAdmin.getSubscription(
          GSubscriptionName.of(subscriptionName.projectId, subscriptionName.subscription)
        )
      )
      .map { gSub =>
        Some(
          Subscription(
            topicName = TopicName(subscriptionName.projectId, gSub.getTopic),
            name = SubscriptionName(subscriptionName.projectId, gSub.getName),
            filter = Option(gSub.getFilter).map(SubscriberFilter.of),
            enableOrdering = gSub.getEnableMessageOrdering,
            expiration = (if (gSub.hasExpirationPolicy) Some(gSub.getExpirationPolicy) else None)
              .map(policy => policy.getTtl.getSeconds.seconds),
            deadLettersSettings = (if (gSub.hasDeadLetterPolicy) Some(gSub.getDeadLetterPolicy) else None)
              .flatMap: policy =>
                TopicName
                  .parse(policy.getDeadLetterTopic)
                  .toOption
                  .map(DeadLettersSettings(_, policy.getMaxDeliveryAttempts)),
          )
        )
      }
      .catchSome { case _: NotFoundException => ZIO.none }

  private def updateSubscriptionIfExist(
    subscriptionAdmin: SubscriptionAdminClient,
    update: Subscription,
  ): ZIO[Any, Throwable, Unit] = {

    /*
     * enable_message_ordering is read only
     * expiration_policy is not support by emulator
     * filter is not support by emulator
     */
    def buildUpdateRequest(subscription: GSubscription): UpdateSubscriptionRequest = UpdateSubscriptionRequest
      .newBuilder()
      .setSubscription(subscription)
      .setUpdateMask(
        FieldMask
          .newBuilder()
          .addPaths("dead_letter_policy")
          .build()
      )
      .build()

    (for {
      currentSetting <- fetchCurrentSubscription(subscriptionAdmin, update.name)
      _ <- ZIO.whenCase(currentSetting) {
             case Some(setting) if setting.deadLettersSettings != update.deadLettersSettings =>
               ZIO.attempt(
                 subscriptionAdmin.updateSubscription(buildUpdateRequest(buildGSubscription(update)))
               )
           }
    } yield ()).unit

  }

  def createOrUpdateWithClient(
    subscriptionAdmin: SubscriptionAdminClient,
    subscription: Subscription,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, Unit] =
    for {
      _            <- createDeadLettersTopicIfNeeded(connection, subscription)
      gSubscription = buildGSubscription(subscription)
      _ <-
        ZIO.attempt(subscriptionAdmin.createSubscription(gSubscription)).unit.catchSome {
          case _: AlreadyExistsException =>
            updateSubscriptionIfExist(
              subscriptionAdmin = subscriptionAdmin,
              update = subscription,
            )
        }
    } yield ()

  def createTempSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
    subscriptionFilter: Option[SubscriberFilter],
    maxTtl: Duration,
    enableOrdering: Boolean,
    connection: PubsubConnectionConfig,
  ): RIO[Scope, Subscription] = for {
    subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
    subscriptionId     = GSubscriptionName.of(subscriptionName.projectId, subscriptionName.subscription)
    topicId            = GTopicName.of(topicName.projectId, topicName.topic)

    expirationPolicy =
      GExpirationPolicy.newBuilder().setTtl(ProtoDuration.newBuilder().setSeconds(maxTtl.getSeconds())).build()
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
    name = subscriptionName,
    filter = subscriptionFilter,
    enableOrdering = enableOrdering,
    expiration = Some(maxTtl),
    deadLettersSettings = None,
  )
}
