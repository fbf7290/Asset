package com.ktmet.asset.api

import play.api.libs.json.{Format, Json}

case class UserId(socialType:String, socialId:String){
  override def toString: String = s"${socialType}_${socialId}"
}
object UserId{
  implicit val format:Format[UserId] = Json.format
  def empty:UserId = UserId("","")
}

case class UserState(userId:UserId, accessToken:Option[String])
object UserState{
  implicit val format:Format[UserState] = Json.format
  def empty:UserState = UserState(UserId.empty, None)
}