package com.ktmet.asset.api.message

import play.api.libs.json.{Format, Json}


case class SocialLoggingInMessage(socialType:String, socialToken:String)
object SocialLoggingInMessage{
  implicit val format:Format[SocialLoggingInMessage] = Json.format
}

case class LoginMessage(userId:String, accessToken:String, refreshToken:String)
object LoginMessage{
  implicit val format:Format[LoginMessage] = Json.format
}

case class TokenMessage(accessToken:String, refreshToken:Option[String])
object TokenMessage{
  implicit val format:Format[TokenMessage] = Json.format
}
case class RefreshingTokenMessage(userId:String, accessToken:String, refreshToken:String)
object RefreshingTokenMessage{
  implicit val format:Format[RefreshingTokenMessage] = Json.format
}

case class UserMessage(userId: String, portfolios: List[String])
object UserMessage{
  implicit val format:Format[UserMessage] = Json.format
}
