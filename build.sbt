import com.lightbend.lagom.core.LagomVersion

organization in ThisBuild := "com.ktmet.asset"
name := "Asset"

version := "0.1"
scalaVersion := "2.13.3"



val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.7" % "provided"
val scalaUuid = "io.jvm.uuid" %% "scala-uuid" % "0.3.1"
val jwt ="com.pauldijou" %% "jwt-core" % "4.3.0"
val jwtJson = "com.pauldijou" %% "jwt-play-json" % "4.3.0"
val radixTree = "com.rklaehn" %% "radixtree" % "0.5.1"

val slf4j = "org.slf4j" % "slf4j-api" % "1.7.28"
val jsoup = "org.jsoup" % "jsoup" % "1.8.2"
val yahooFinance = "com.yahoofinance-api" % "YahooFinanceAPI" % "3.15.0"
val cats = "org.typelevel" %% "cats-core" % "2.1.1"


val akkaDiscoveryKubernetesApi = "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "1.0.8"
val lagomScaladslAkkaDiscovery = "com.lightbend.lagom" %% "lagom-scaladsl-akka-discovery-service-locator" % LagomVersion.current

ThisBuild / scalacOptions ++= List("-encoding", "utf8", "-deprecation", "-feature", "-unchecked", "-Xfatal-warnings", "-language:higherKinds", "-Ypartial-unification")


def dockerSettings = Seq(
  dockerBaseImage := "adoptopenjdk/openjdk8"
)


lazy val commonApi = (project in file("common-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )


lazy val collectorApi = (project in file("collector-api"))
  .settings(
    name := "collectApi",
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )



lazy val collectorImpl = (project in file("collector-impl"))
  .enablePlugins(LagomScala)
  .settings(
    name := "collector",
    libraryDependencies ++= Seq(
      macwire,
      yahooFinance,
      jsoup,
      slf4j,
      cats,
      lagomScaladslAkkaDiscovery,
      akkaDiscoveryKubernetesApi,
      lagomScaladslPersistenceCassandra
    )
  ).settings(dockerSettings)
  .dependsOn(commonApi, collectorApi)

lazy val assetApi = (project in file("asset-api"))
  .settings(
    libraryDependencies ++= Seq(
      scalaUuid,
      cats,
      lagomScaladslApi
    )
  ).dependsOn(commonApi, collectorApi)



lazy val assetImpl = (project in file("asset-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslPubSub,
      lagomScaladslTestKit,
      cats,
      jwt,
      jwtJson,
      macwire,
      scalaUuid,
      radixTree,
      lagomScaladslAkkaDiscovery,
      akkaDiscoveryKubernetesApi
    )
  ).settings(dockerSettings)
  .dependsOn(commonApi, assetApi, collectorApi, collectorImpl)


lagomCassandraEnabled in ThisBuild := false
