import play.sbt.PlayImport._

name := """smg"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

// fixing these to avoid intellij complains
dependencyOverrides += "org.scala-lang" % "scala-library" % "2.11.8"
dependencyOverrides += "org.apache.httpcomponents" % "httpclient" % "4.3.4" //(4.3.4, 4.4.1)
dependencyOverrides += "org.scala-lang" % "scala-reflect" % "2.11.8" //(2.11.6, 2.11.8)
dependencyOverrides += "com.google.guava" % "guava" % "18.0" //(18.0, 16.0.1)
dependencyOverrides += "commons-logging" % "commons-logging" % "1.2" //(1.1.3, 1.2)
dependencyOverrides += "org.scala-lang.modules" % "scala-parser-combinators_2.11" % "1.0.4" //:(1.0.1, 1.0.4)
dependencyOverrides += "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.5" //:(1.0.1, 1.0.5, 1.0.4)

libraryDependencies ++= Seq(
  "org.apache.httpcomponents" % "httpclient" % "4.3.4",
  "org.apache.httpcomponents" % "httpcore" % "4.3.2",
  "commons-logging" % "commons-logging" % "1.1.3",
  jdbc,
  cache,
  ws,
  "org.yaml" % "snakeyaml" % "1.16",
  specs2 % Test,
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "org.scalatestplus" % "play_2.11" % "1.4.0-M4" % "test"
)
//libraryDependencies += filters

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

//scalacOptions += "-Ylog-classpath"
