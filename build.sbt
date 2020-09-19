import com.lightbend.lagom.core.LagomVersion

organization in ThisBuild := "com.ktmet.asset"
name := "Asset"

version := "0.1"
scalaVersion := "2.13.3"



val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.7" % "provided"
val scalaUuid = "io.jvm.uuid" %% "scala-uuid" % "0.3.1"
val jwt ="com.pauldijou" %% "jwt-core" % "4.3.0"


val akkaDiscoveryKubernetesApi = "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "1.0.8"
val lagomScaladslAkkaDiscovery = "com.lightbend.lagom" %% "lagom-scaladsl-akka-discovery-service-locator" % LagomVersion.current

ThisBuild / scalacOptions ++= List("-encoding", "utf8", "-deprecation", "-feature", "-unchecked", "-Xfatal-warnings")


def dockerSettings = Seq(
  dockerBaseImage := "adoptopenjdk/openjdk8"
)


lazy val commonApi = (project in file("common-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )


lazy val assetApi = (project in file("asset-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  ).dependsOn(commonApi)



lazy val assetImpl = (project in file("asset-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslPubSub,
      lagomScaladslTestKit,
      macwire,
      scalaUuid,
      lagomScaladslAkkaDiscovery,
      akkaDiscoveryKubernetesApi
    )
  ).settings(dockerSettings)
  .dependsOn(commonApi, assetApi)


lagomCassandraEnabled in ThisBuild := false
