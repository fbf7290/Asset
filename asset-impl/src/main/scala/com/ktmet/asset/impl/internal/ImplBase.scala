package com.ktmet.asset.impl.internal

import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.util.Timeout
import play.api.libs.ws.WSClient
import akka.actor.typed.scaladsl.adapter._
import com.ktmet.asset.api.{AssetService, AssetSettings, PortfolioId, UserId}
import com.ktmet.asset.common.api.AuthorizationException
import com.ktmet.asset.impl.actor.StatisticSharding
import com.ktmet.asset.impl.entity.{PortfolioEntity, UserEntity}
import com.lightbend.lagom.scaladsl.api.transport.RequestHeader
import pdi.jwt.{JwtAlgorithm, JwtJson}
import pdi.jwt.exceptions.JwtExpirationException

import scala.concurrent.ExecutionContext
import scala.reflect.internal.util.StatisticsStatics
import scala.util.{Failure, Success}

trait ImplBase extends AssetService{

  protected val clusterSharding: ClusterSharding
  protected val system: ActorSystem
  protected val wsClient:WSClient
  protected implicit val ec: ExecutionContext
  protected implicit val timeout:Timeout

  implicit  val typedSystem = system.toTyped


  protected def userEntityRef(userId: UserId): EntityRef[UserEntity.Command] =
    clusterSharding.entityRefFor(UserEntity.typeKey, userId.value)

  protected def userEntityRef(userId: String): EntityRef[UserEntity.Command] =
    clusterSharding.entityRefFor(UserEntity.typeKey, userId)


  protected def portfolioEntityRef(portfolioId: String): EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId)

  protected def portfolioEntityRef(portfolioId: PortfolioId): EntityRef[PortfolioEntity.Command] =
    clusterSharding.entityRefFor(PortfolioEntity.typeKey, portfolioId.value)


  protected def statisticShardingRef(portfolioId: String): EntityRef[StatisticSharding.Command] =
    clusterSharding.entityRefFor(StatisticSharding.typeKey, portfolioId)

  protected def statisticShardingRef(portfolioId: PortfolioId): EntityRef[StatisticSharding.Command] =
    clusterSharding.entityRefFor(StatisticSharding.typeKey, portfolioId.value)
}

