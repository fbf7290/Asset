package com.ktmet.asset.impl.internal

import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import play.api.libs.ws.WSClient
import akka.actor.typed.scaladsl.adapter._
import com.ktmet.asset.api.AssetService
import com.ktmet.asset.impl.entity.UserEntity

import scala.concurrent.ExecutionContext

trait ImplBase extends AssetService{

  protected val clusterSharding: ClusterSharding
  protected val system: ActorSystem
  protected val wsClient:WSClient
  protected implicit val ec: ExecutionContext
  protected implicit val timeout:Timeout

  implicit  val typedSystem = system.toTyped


  protected def userEntityRef(userId: String): EntityRef[UserEntity.Command] =
    clusterSharding.entityRefFor(UserEntity.typeKey, userId)

}
