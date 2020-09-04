import play.sbt.PlayImport._

name := """smg"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.12"

// fixing these to suppress the
// There may be incompatibilities among your library dependencies; run 'evicted' to see detailed eviction warnings.
dependencyOverrides += "com.google.guava" % "guava" % "27.1-jre"
dependencyOverrides += "org.scala-lang.modules" % "scala-java8-compat_2.11" % "0.9.0"

//dependencyOverrides += "org.scala-lang" % "scala-library" % "2.12.4"
//dependencyOverrides += "org.apache.httpcomponents" % "httpclient" % "4.3.4" //(4.3.4, 4.4.1)
//dependencyOverrides += "org.scala-lang" % "scala-reflect" % "2.12.4" //(2.11.6, 2.12.4)
//dependencyOverrides += "com.google.guava" % "guava" % "18.0" //(18.0, 16.0.1)
//dependencyOverrides += "commons-logging" % "commons-logging" % "1.2" //(1.1.3, 1.2)
//dependencyOverrides += "org.scala-lang.modules" % "scala-parser-combinators_2.11" % "1.0.4" //:(1.0.1, 1.0.4)
//dependencyOverrides += "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.5" //:(1.0.1, 1.0.5, 1.0.4)

libraryDependencies ++= Seq(
  guice,
  "commons-logging" % "commons-logging" % "1.1.3",
  jdbc,
  ws,
  "org.yaml" % "snakeyaml" % "1.26",
  specs2 % Test,
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "org.scalatestplus.play" % "scalatestplus-play_2.11" % "4.0.3" % "test"
)
//libraryDependencies += filters

libraryDependencies += "com.typesafe.play" %% "play-iteratees" % "2.6.1"
libraryDependencies += "javax.inject" % "javax.inject" % "1"
libraryDependencies += "com.google.inject" % "guice" % "4.2.2"
libraryDependencies += "io.fabric8" % "kubernetes-client" % "4.11.1"

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

//scalacOptions += "-Ylog-classpath"
