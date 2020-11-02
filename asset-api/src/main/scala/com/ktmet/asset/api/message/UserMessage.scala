package com.ktmet.asset.api.message

import play.api.libs.json.{Format, Json}


case class SocialLoggingIn(socialType:String, socialToken:String)
object SocialLoggingIn{
  implicit val format:Format[SocialLoggingIn] = Json.format
}

case class LoginMessage(userId:String, accessToken:String, refreshToken:String)
object LoginMessage{
  implicit val format:Format[LoginMessage] = Json.format
}

case class TokenMessage(accessToken:String, refreshToken:Option[String])
object TokenMessage{
  implicit val format:Format[TokenMessage] = Json.format
}
case class RefreshingToken(userId:String, accessToken:String, refreshToken:String)
object RefreshingToken{
  implicit val format:Format[RefreshingToken] = Json.format
}

case class UserMessage(userId: String, portfolios: List[String])
object UserMessage{
  implicit val format:Format[UserMessage] = Json.format
}
