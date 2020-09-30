package com.asset.collector.api

object Exception {
  case class ExternalResourceException(msg:String="") extends ClientException(404, "ExternalResourceException", "Check your external resource")
}