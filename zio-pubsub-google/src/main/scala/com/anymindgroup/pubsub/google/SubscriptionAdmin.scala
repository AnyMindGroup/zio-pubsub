package com.anymindgroup.pubsub.google

import java.util.concurrent.TimeUnit

import com.anymindgroup.pubsub.sub.{DeadLettersSettings, SubscriberFilter, Subscription}
import com.google.api.gax.rpc.{AlreadyExistsException, NotFoundException}
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.protobuf.{Duration as ProtoDuration, FieldMask}
import com.google.pubsub.v1.*
import com.google.pubsub.v1.Subscription as GSubscription

import zio.{Duration, RIO, RLayer, Scope, ZIO, ZLayer, durationLong}

@deprecated("will be removed from release 0.3. Use Google's Java clients directly instead for admin APIs.", since = "0.2.11")
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

  def createOrUpdate(
    connection: PubsubConnectionConfig,
    subscription: Subscription,
  ): RIO[Scope, Unit] =
    for {
      subscriptionAdmin <- SubscriptionAdmin.makeClient(connection)
      _                 <- createOrUpdate(connection, subscriptionAdmin, subscription)
    } yield ()

  private def createDeadLettersTopicIfNeeded(
    connection: PubsubConnectionConfig,
    subscription: Subscription,
  ): RIO[Scope, Unit] = subscription.deadLettersSettings
    .map(s =>
      TopicAdmin
        .makeClient(connection)
        .flatMap(admin =>
          ZIO
            .attempt(
              admin.getTopic(
                ProjectTopicName
                  .of(connection.project.name, s.deadLetterTopicName)
                  .toString
              )
            )
            .unit
            .catchSome { case _: NotFoundException =>
              for {
                _ <- ZIO.logInfo(s"Dead letter topic for subscription ${subscription.name} not found! Creating...")
                _ <- ZIO.attempt(admin.createTopic(TopicName.format(connection.project.name, s.deadLetterTopicName)))
                _ <- ZIO.logInfo(
                       s"Created dead letter topic for subscription ${subscription.name}: ${s.deadLetterTopicName}"
                     )
              } yield ()
            }
        )
    )
    .getOrElse(ZIO.unit)

  private def buildGSubscription(projectName: String, subscription: Subscription): GSubscription = {
    val topicId        = TopicName.of(projectName, subscription.topicName)
    val subscriptionId = SubscriptionName.of(projectName, subscription.name)
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
            ProjectTopicName.of(projectName, s.deadLetterTopicName).toString
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

  def fetchCurrentSubscription(
    subscriptionAdmin: SubscriptionAdminClient,
    projectName: String,
    subscriptionName: String,
  ): ZIO[Any, Throwable, Option[Subscription]] =
    (ZIO
      .attempt(subscriptionAdmin.getSubscription(SubscriptionName.of(projectName, subscriptionName)))
      .map { gSub =>
        Some(
          Subscription(
            topicName = gSub.getTopic,
            name = gSub.getName,
            filter = Option(gSub.getFilter).map(SubscriberFilter.of),
            enableOrdering = gSub.getEnableMessageOrdering,
            expiration = (if (gSub.hasExpirationPolicy) Some(gSub.getExpirationPolicy) else None)
              .map(policy => policy.getTtl.getSeconds.seconds),
            deadLettersSettings = (if (gSub.hasDeadLetterPolicy) Some(gSub.getDeadLetterPolicy) else None)
              .map(policy =>
                DeadLettersSettings(TopicName.parse(policy.getDeadLetterTopic).getTopic, policy.getMaxDeliveryAttempts)
              ),
          )
        )
      })
      .catchSome { case _: NotFoundException => ZIO.none }
  private def updateSubscriptionIfExist(
    projectName: String,
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
      currentSetting <- fetchCurrentSubscription(subscriptionAdmin, projectName, update.name)
      _ <- ZIO.whenCase(currentSetting) {
             case Some(setting) if setting.deadLettersSettings != update.deadLettersSettings =>
               ZIO.attempt(
                 subscriptionAdmin.updateSubscription(buildUpdateRequest(buildGSubscription(projectName, update)))
               )
           }
    } yield ()).unit

  }

  def createOrUpdate(
    connection: PubsubConnectionConfig,
    subscriptionAdmin: SubscriptionAdminClient,
    subscription: Subscription,
  ): RIO[Scope, Unit] =
    for {
      _            <- createDeadLettersTopicIfNeeded(connection, subscription)
      gSubscription = buildGSubscription(connection.project.name, subscription)
      _ <-
        ZIO.attempt(subscriptionAdmin.createSubscription(gSubscription)).unit.catchSome {
          case _: AlreadyExistsException =>
            updateSubscriptionIfExist(
              projectName = connection.project.name,
              subscriptionAdmin = subscriptionAdmin,
              update = subscription,
            )
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
