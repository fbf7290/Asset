package com.ktmet.asset.api

object AssetSettings {

  val jwtSecretKey = sys.env.get("JWT_SECRET_KEY") match {
    case Some(jwt_secret_key) => jwt_secret_key
    case None => "secretKey"
  }

}
