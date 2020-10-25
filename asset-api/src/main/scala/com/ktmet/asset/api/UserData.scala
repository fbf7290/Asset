package com.ktmet.asset.api

import play.api.libs.json.{Format, Json}


case class UserId(value: String){
  override def toString: String = value
  override def canEqual(a: Any) = a.isInstanceOf[UserId]

  override def equals(that: Any): Boolean =
    that match {
      case that: UserId =>
        that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode:Int = {
    value.hashCode
  }
}
object UserId{
  implicit val format:Format[UserId] = Json.format

  def userIdFormat(socialType:String, socialId:String) = s"${socialType}_${socialId}"
  def empty:UserId = UserId("")
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

case class UserState(userId:UserId, tokens:List[Token], portfolios: List[PortfolioId], maxPortfolioSize: Int){
  def loggedIn(token:Token) = copy(tokens = token::tokens take AssetSettings.tokenListSize)
  def loggedOut(accessToken:String) = copy(tokens = tokens.filterNot(_==accessToken))
  def containToken(accessToken:String):Boolean = tokens.contains(accessToken)
  def containToken(token:Token):Boolean = tokens.contains(token)
  def refreshToken(lastToken:Token, newToken:Token) = copy(tokens= newToken :: tokens.filterNot(_==lastToken))
  def addPortfolio(portfolioId: PortfolioId) = copy(portfolios = portfolioId :: portfolios)
  def deletePortfolio(portfolioId: PortfolioId) = copy(portfolios = portfolios.filterNot(_ == portfolioId))
}
object UserState{
  implicit val format:Format[UserState] = Json.format
  def empty:UserState = UserState(UserId.empty, List.empty, List.empty, AssetSettings.defaultMaxPortfolioSize)
}