package com.ktmet.asset.impl

import java.net.URLDecoder

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.asset.collector.api.{CollectorService, KrwUsd, NowPrice, Stock}
import com.ktmet.asset.api.{AssetService, AutoCompleteMessage, Category, PortfolioId, PortfolioState}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext
import akka.actor.typed.scaladsl.adapter._
import com.ktmet.asset.impl.actor.{NowPriceActor, StockAutoCompleter}
import akka.actor.typed.scaladsl.AskPattern._
import com.ktmet.asset.api.message.{CreatingPortfolioMessage, PortfolioCreatedMessage, TimestampMessage}
import com.ktmet.asset.common.api.ClientException
import com.ktmet.asset.impl.actor.StockAutoCompleter.SearchResponse
import com.ktmet.asset.impl.entity.{PortfolioEntity, UserEntity}
import com.lightbend.lagom.scaladsl.api.transport.{BadRequest, ResponseHeader}
import com.lightbend.lagom.scaladsl.server.ServerServiceCall
import io.jvm.uuid._








class AssetServiceImpl(protected val clusterSharding: ClusterSharding,
                       protected val system: ActorSystem,
                       protected val wsClient: WSClient)
                      (implicit protected val  ec: ExecutionContext
                       , implicit protected val timeout:Timeout
                       , implicit val collectorService: CollectorService) extends UserServiceImplPart {

  val stockAutoCompleter = system.spawn(StockAutoCompleter(), "stockAutoCompleter")
  val nowPriceActor = system.spawn(NowPriceActor(), "nowPriceActor")

  override def autoCompleteStock(prefix:String): ServiceCall[NotUsed, AutoCompleteMessage] = authenticate{ _ =>
    ServerServiceCall{ (_,_) =>
      stockAutoCompleter.ask[StockAutoCompleter.Response](reply => StockAutoCompleter.Search(URLDecoder.decode(prefix, "UTF-8"), reply))
        .collect{
          case StockAutoCompleter.SearchResponse(koreaStocks, usaStocks) =>
            (ResponseHeader.Ok.withStatus(200), AutoCompleteMessage(koreaStocks.toSeq, usaStocks.toSeq))
          case StockAutoCompleter.Initializing =>
            (ResponseHeader.Ok.withStatus(204), AutoCompleteMessage.empty)
        }
    }
  }

  override def getNowPrice(code: String): ServiceCall[NotUsed, NowPrice] = authenticate { _ =>
    ServerServiceCall{ (_,_) =>
      nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetPrice(code, reply))
        .collect{
          case NowPriceActor.PriceResponse(price) => (ResponseHeader.Ok.withStatus(200), price)
          case NowPriceActor.NotFoundStock => throw new ClientException(404, "NotFoundStock", "")
        }
    }
  }

  override def getNowKrwUsd: ServiceCall[NotUsed, KrwUsd] = authenticate { _ =>
    ServerServiceCall{ (_,_) =>
      nowPriceActor.ask[NowPriceActor.Response](reply => NowPriceActor.GetKrwUsd(reply))
        .collect{
          case NowPriceActor.KrwUsdResponse(krwUsd) => (ResponseHeader.Ok.withStatus(200), krwUsd)
        }
    }
  }

  override def createPortfolio: ServiceCall[CreatingPortfolioMessage, PortfolioCreatedMessage] = authenticate{ userId =>
    ServerServiceCall{ (_, createPortfolioMessage) =>
      if(createPortfolioMessage.name.size > 10) throw BadRequest("")

      val portfolioId = PortfolioId(UUID.random.string)
      for {
        _ <- userEntityRef(userId).ask[UserEntity.Response](reply => UserEntity.AddPortfolio(portfolioId, reply))
            .collect{
              case UserEntity.Yes => None
              case UserEntity.TooManyPortfolioException => throw UserEntity.TooManyPortfolioException
            }
        r <- portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
                PortfolioEntity.CreatePortfolio(portfolioId, userId, createPortfolioMessage.name, reply))
            .collect{
              case PortfolioEntity.CreateResponse(portfolioId, name, updateTimestamp) => PortfolioCreatedMessage(portfolioId.value, updateTimestamp)
              case m: ClientException =>
                userEntityRef(userId).ask[UserEntity.Response](reply => UserEntity.DeletePortfolio(portfolioId, reply))
                throw m
            }
      } yield {
        (ResponseHeader.Ok.withStatus(201), r)
      }
    }
  }

  override def deletePortfolio(portfolioId: String): ServiceCall[NotUsed, Done] = authenticate{ userId =>
    ServerServiceCall{ (_, _) =>
      for {
        _ <- portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply =>
                PortfolioEntity.DeletePortfolio(userId, reply))
            .collect{
              case PortfolioEntity.Yes => None
              case m: ClientException => throw m
            }
        _ <- userEntityRef(userId).ask[UserEntity.Response](reply =>
                UserEntity.DeletePortfolio(PortfolioId(portfolioId), reply))
      } yield {
        (ResponseHeader.Ok.withStatus(204), Done)
      }
    }
  }

  override def getPortfolio(portfolioId: String): ServiceCall[NotUsed, PortfolioState] =  authenticate{ userId =>
    ServerServiceCall{ (_, _) =>
      portfolioEntityRef(portfolioId).ask[PortfolioEntity.Response](reply => PortfolioEntity.GetPortfolio(reply))
        .collect{
          case PortfolioEntity.PortfolioResponse(portfolioState) =>
            (ResponseHeader.Ok.withStatus(200), portfolioState)
          case m: ClientException => throw m
        }
    }
  }

}
