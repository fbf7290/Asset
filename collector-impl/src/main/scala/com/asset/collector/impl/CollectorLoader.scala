package com.asset.collector.impl

import akka.Done
import akka.util.Timeout
import com.asset.collector.api.{CollectorService, Country}
import com.asset.collector.impl.repo.stock.{StockRepo, StockRepoAccessor}
import com.lightbend.lagom.internal.persistence.cluster.ClusterStartupTask
import com.lightbend.lagom.scaladsl.akka.discovery.AkkaDiscoveryComponents
import com.lightbend.lagom.scaladsl.client.ConfigurationServiceLocatorComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import com.softwaremill.macwire.wire
import play.api.libs.json.{Format, Json}
import play.api.libs.ws.ahc.AhcWSComponents
import cats.instances.future._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class CollectorLoader extends LagomApplicationLoader {
  override def load(context: LagomApplicationContext): LagomApplication =
//    new CollectorApplication(context) with ConfigurationServiceLocatorComponents
    new CollectorApplication(context) with AkkaDiscoveryComponents


  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new CollectorApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[CollectorService])
}

abstract class CollectorApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents {

  implicit lazy val timeout:Timeout = Timeout(5.seconds)

  override lazy val lagomServer = serverFor[CollectorService](wire[CollectorServiceImpl])


  val serializers1 : Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[CreatingUser]
  )

  case class CreatingUser(userId:String, pwd:String, department:String)
  object CreatingUser {
    implicit val format: Format[CreatingUser] = Json.format
  }

  override lazy val jsonSerializerRegistry = new JsonSerializerRegistry {
    override def serializers  = Seq(
      JsonSerializer[CreatingUser]
    )
  }

  lazy val stockDb = StockRepo(cassandraSession)

  ClusterStartupTask(actorSystem, "Init", () => {
    (StockRepoAccessor.createStockTable(Country.KOREA).run(stockDb) zip
      StockRepoAccessor.createStockTable(Country.USA).run(stockDb) zip
      StockRepoAccessor.createNowPriceTable(Country.KOREA).run(stockDb) zip
      StockRepoAccessor.createNowPriceTable(Country.USA).run(stockDb) zip
      StockRepoAccessor.createPriceTable(Country.KOREA).run(stockDb) zip
      StockRepoAccessor.createPriceTable(Country.USA).run(stockDb)).map(_ => Done)
  }, 60.seconds, None, 3.seconds, 30.seconds, 0.2)
}