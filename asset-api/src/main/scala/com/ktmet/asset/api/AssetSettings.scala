package com.ktmet.asset.api

object AssetSettings {

  val jwtSecretKey = sys.env.get("JWT_SECRET_KEY") match {
    case Some(jwt_secret_key) => jwt_secret_key
    case None => "secretKey"
  }

  val tokenListSize = sys.env.get("TOKEN_LIST_SIZE") match {
    case Some(token_list_size) => token_list_size.toInt
    case None => 5   // 동시 접속 5
  }

  val accessTokenExpiredSecond  = sys.env.get("ACCESS_TOKEN_EXPIRED_SECOND") match {
    case Some(access_token_expired_second) => access_token_expired_second.toLong
    case None => 21600   // 6시간
  }

  val refreshTokenExpiredSecond  = sys.env.get("REFRESH_TOKEN_EXPIRED_SECOND") match {
    case Some(refresh_token_expired_second) => refresh_token_expired_second.toLong
    case None => 2592000   // 30일
  }

  val refreshTokenAutoRefreshSecond = sys.env.get("REFRESH_TOKEN_AUTO_REFRESH_SECOND") match {
    case Some(refresh_token_auto_refresh_second) => refresh_token_auto_refresh_second.toLong
    case None => 604800   // 7일
  }

  val kakao = sys.env.get("NAVI_KAKAO") match {
    case Some(navi_kakao) => navi_kakao
    case None => "https://kapi.kakao.com"
  }

  val defaultMaxPortfolioSize = sys.env.get("DEFAULT_MAX_PORTFOLIO_SIZE") match {
    case Some(value) => value.toInt
    case None => 2   // 유저 최대 포트폴리오 개수
  }

}
