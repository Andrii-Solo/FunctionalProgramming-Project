ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.5"

lazy val root = (project in file("."))
  .settings(
    name := "FinalProject"
  )

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % "0.23.30",
  "org.http4s" %% "http4s-ember-server" % "0.23.30",
  "org.http4s" %% "http4s-ember-client" % "0.23.30",
  "org.http4s" %% "http4s-circe" % "0.23.30",
  "io.circe" %% "circe-generic" % "0.14.13",
  "com.lihaoyi" %% "requests" % "0.9.0",
  "com.lihaoyi" %% "upickle" % "4.1.0"
)
libraryDependencies += "org.http4s" %% "http4s-blaze-server" % "0.23.17"


