package com.ktmet.asset.impl

import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.ktmet.asset.api.AssetService
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext

class AssetServiceImpl(protected val clusterSharding: ClusterSharding,
                       protected val system: ActorSystem,
                       protected val wsClient: WSClient)
                      (implicit protected val  ec: ExecutionContext, implicit protected val timeout:Timeout) extends UserServiceImplPart {

}
