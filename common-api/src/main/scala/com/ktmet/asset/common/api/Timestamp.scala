package com.ktmet.asset.common.api

import java.time.{Instant, ZoneId, ZonedDateTime}

import scala.concurrent.duration.Duration

object Timestamp {
  val zoneId = ZoneId.of("UTC+09:00")

  def now = Instant.now().getEpochSecond
  def nowMilli = Instant.now().toEpochMilli()
  def nowDate = ZonedDateTime.now(Timestamp.zoneId).withHour(0).withMinute(0).withSecond(0).toEpochSecond

  def nowHour = ZonedDateTime.now(Timestamp.zoneId).getHour
  def nowMinute = ZonedDateTime.now(Timestamp.zoneId).getMinute

  def afterDuration(duration: Duration) = Instant.now().plusSeconds(duration.toSeconds).getEpochSecond
}
