package com.ktmet.asset.api

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



case class UserId(socialType:String, socialId:String){
  override def toString: String = s"${socialType}_${socialId}"
}
object UserId{
  implicit val format:Format[UserId] = Json.format
  def empty:UserId = UserId("","")
}

case class Token(accessToken:String, refreshToken:String){
  override def equals(obj: Any): Boolean = obj match {
    case that: Token => that.accessToken.equals(this.accessToken)
    case that: String => that.equals(this.accessToken)
    case _ => false
  }
  override def hashCode(): Int = accessToken.hashCode
}
object Token{
  implicit val format:Format[Token] = Json.format
}

case class UserState(userId:UserId, tokens:List[Token]){
  def loggedIn(token:Token) = copy(tokens = token::tokens take AssetSettings.tokenListSize)
  def loggedOut(accessToken:String) = copy(tokens = tokens.filterNot(_==accessToken))
  def containToken(accessToken:String):Boolean = tokens.contains(accessToken)
  def containToken(token:Token):Boolean = tokens.contains(token)
  def refreshToken(lastToken:Token, newToken:Token) = copy(tokens= newToken :: tokens.filterNot(_==lastToken))
}
object UserState{
  implicit val format:Format[UserState] = Json.format
  def empty:UserState = UserState(UserId.empty, List.empty)
}