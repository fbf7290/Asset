package com.ktmet.asset.common.api

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}

import org.joda.time.format.DateTimeFormat

import scala.concurrent.duration.Duration

object Timestamp {
  val zoneId = ZoneId.of("UTC+09:00")

  def now = Instant.now().getEpochSecond
  def nowMilli = Instant.now().toEpochMilli()

  def nowHour = ZonedDateTime.now(Timestamp.zoneId).getHour
  def nowMinute = ZonedDateTime.now(Timestamp.zoneId).getMinute

  def tomorrow(timestamp: Long) = {
    val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), Timestamp.zoneId)
    base.withDayOfMonth(base.getDayOfMonth+1).withHour(0).withMinute(0).withSecond(0).toEpochSecond
  }


  def timestampToDateString(timestamp: Long): String = {
    val base = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), Timestamp.zoneId)
    val date = base.withHour(0).withMinute(0).withSecond(0)
    val year = date.getYear()
    val month = date.getMonthValue()
    val day = date.getDayOfMonth()
    f"$year%04d$month%02d$day%02d"
  }
  def timestampToTomorrowDateString(timestamp: Long): String = {
    val base = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), Timestamp.zoneId)
    val tomorrow = base.plusDays(1).withHour(0).withMinute(0).withSecond(0)
    val year = tomorrow.getYear()
    val month = tomorrow.getMonthValue()
    val day = tomorrow.getDayOfMonth()
    f"$year%04d$month%02d$day%02d"
  }
  def rangeDateString(fromDateString: String, toDateString: String): List[String] = {
    val format = DateTimeFormat.forPattern("yyyyMMdd'T'HH:mm:ss")
    var fromDate = format.parseDateTime(s"${fromDateString}T00:00:00")
    val endDate = format.parseDateTime(s"${toDateString}T00:00:00")

//    var fromDate: ZonedDateTime = ZonedDateTime.parse(s"${fromDateString} 00:00:00 KST", DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z"))
//    val endDate: ZonedDateTime = ZonedDateTime.parse(s"${toDateString} 00:00:00 KST", DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z"))
    var result = List.empty[String]
    while(fromDate.isBefore(endDate)) {
      result = DateTimeFormat.forPattern("yyyyMMdd").print(fromDate) :: result
      fromDate = fromDate.plusDays(1)
    }
    result.reverse
  }

  def afterDuration(duration: Duration) = Instant.now().plusSeconds(duration.toSeconds).getEpochSecond
  def beforeDuration(duration: Duration) = Instant.now().minusSeconds(duration.toSeconds).getEpochSecond
  def beforeDuration(timestamp: Long, duration: Duration) = Instant.ofEpochSecond(timestamp).minusSeconds(duration.toSeconds).getEpochSecond

  def getDateStringBeforeDuration(date: String, duration: Duration) = {
    val format = DateTimeFormat.forPattern("yyyyMMdd'T'HH:mm:ss")
    val dateObj = format.parseDateTime(s"${date}T00:00:00").minus(duration.toMillis)
    DateTimeFormat.forPattern("yyyyMMdd").print(dateObj)
  }
}
