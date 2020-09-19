package com.ktmet.asset.api

import play.api.libs.json.{Format, Json}



case class SocialLoggingInMessage(socialType:String, socialToken:String)
object SocialLoggingInMessage{
  implicit val format:Format[SocialLoggingInMessage] = Json.format
}
case class LoginMessage(userId:String, accessToken:String)
object LoginMessage{
  implicit val format:Format[LoginMessage] = Json.format
}

case class TokenMessage(accessToken:String)
object TokenMessage{
  implicit val format:Format[TokenMessage] = Json.format
}
case class RefreshingTokenMessage(userId:String, accessToken:String)
object RefreshingTokenMessage{
  implicit val format:Format[RefreshingTokenMessage] = Json.format
}



case class UserId(socialType:String, socialId:String){
  override def toString: String = s"${socialType}_${socialId}"
}
object UserId{
  implicit val format:Format[UserId] = Json.format
  def empty:UserId = UserId("","")
}

case class UserState(userId:UserId, accessToken:Option[String]){
  def loggedIn(accessToken:String) = copy(accessToken = Some(accessToken))
  def loggedOut = copy(accessToken=None)
}
object UserState{
  implicit val format:Format[UserState] = Json.format
  def empty:UserState = UserState(UserId.empty, None)
}