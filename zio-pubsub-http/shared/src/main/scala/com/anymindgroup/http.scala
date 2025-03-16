package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.*
import com.anymindgroup.pubsub.PubsubConnectionConfig
import sttp.client4.Backend

import zio.{Schedule, Scope, Task, ZIO}

def defaultBackendByConfig(
  config: PubsubConnectionConfig,
  lookupComputeMetadataFirst: Boolean = false,
  refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
  refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
): ZIO[Scope, Throwable, Backend[Task]] = config match
  case PubsubConnectionConfig.Cloud =>
    defaultAccessTokenBackend(
      lookupComputeMetadataFirst = lookupComputeMetadataFirst,
      refreshRetrySchedule = refreshRetrySchedule,
      refreshAtExpirationPercent = refreshAtExpirationPercent,
    )
  case e: PubsubConnectionConfig.Emulator => EmulatorBackend.withDefaultBackend(e)
