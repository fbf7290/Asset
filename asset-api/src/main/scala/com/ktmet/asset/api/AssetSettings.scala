package com.ktmet.asset.api

object AssetSettings {

  val jwtSecretKey = sys.env.get("JWT_SECRET_KEY") match {
    case Some(jwt_secret_key) => jwt_secret_key
    case None => "secretKey"
  }

  val accessTokenExpiredSecond  = sys.env.get("ACCESS_TOKEN_EXPIRED_SECOND") match {
    case Some(access_token_expired_second) => access_token_expired_second.toLong
    case None => 1800   // 30분
  }

  val kakao = sys.env.get("NAVI_KAKAO") match {
    case Some(navi_kakao) => navi_kakao
    case None => "https://kapi.kakao.com"
  }

}
