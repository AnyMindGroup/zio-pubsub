package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.*
import com.anymindgroup.pubsub.model.PubsubConnectionConfig

import zio.Schedule

def defaultBackendByConfig(
  config: PubsubConnectionConfig,
  lookupComputeMetadataFirst: Boolean = false,
  refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
  refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
) = config match
  case PubsubConnectionConfig.Cloud =>
    defaultAccessTokenBackend(
      lookupComputeMetadataFirst = lookupComputeMetadataFirst,
      refreshRetrySchedule = refreshRetrySchedule,
      refreshAtExpirationPercent = refreshAtExpirationPercent,
    )
  case e: PubsubConnectionConfig.Emulator => EmulatorBackend.withDefaultBackend(e)
