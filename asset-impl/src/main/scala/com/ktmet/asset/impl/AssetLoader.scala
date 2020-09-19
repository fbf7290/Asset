package com.ktmet.asset.impl


import akka.cluster.sharding.typed.scaladsl.Entity
import akka.util.Timeout
import com.ktmet.asset.api.AssetService
import com.ktmet.asset.impl.entity.UserEntity
import com.lightbend.lagom.scaladsl.akka.discovery.AkkaDiscoveryComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import play.api.libs.ws.ahc.AhcWSComponents
import com.softwaremill.macwire._
import scala.concurrent.duration._

import scala.collection.immutable

class AssetLoader extends LagomApplicationLoader {
  override def load(context: LagomApplicationContext): LagomApplication =
    new AssetApplication(context) with AkkaDiscoveryComponents

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new AssetApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[AssetService])
}

abstract class AssetApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents {

  override lazy val lagomServer = serverFor[AssetService](wire[AssetServiceImpl])
  implicit lazy val timeout:Timeout = Timeout(5.seconds)

  override lazy val jsonSerializerRegistry = new JsonSerializerRegistry {
    override def serializers  = immutable.Seq.empty[JsonSerializer[_]]
  }
  clusterSharding.init(
    Entity(UserEntity.typeKey) { entityContext =>
      UserEntity(entityContext)
    }
  )
}
