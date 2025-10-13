package com.anymindgroup.pubsub

import zio.*

trait ZIOPubsubTestRuntime:
  def setRuntime: ZLayer[Any, Throwable, Unit] =
    Runtime.enableLoomBasedExecutor ++ Runtime.enableLoomBasedBlockingExecutor
