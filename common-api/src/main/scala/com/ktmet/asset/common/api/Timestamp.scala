package com.ktmet.asset.common.api

import java.time.Instant

import scala.concurrent.duration.Duration

object Timestamp {
  def now = Instant.now().getEpochSecond
  def nowMilli = Instant.now().toEpochMilli();

  def afterDuration(duration: Duration) = Instant.now().plusSeconds(duration.toSeconds).getEpochSecond
}
