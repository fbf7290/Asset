package com.ktmet.asset.impl

import akka.actor.ActorSystem
import com.ktmet.asset.api.AssetService
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext

class AssetServiceImpl(system: ActorSystem, wsClient: WSClient)
                      (implicit ec: ExecutionContext) extends AssetService{

}
