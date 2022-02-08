package com.smule.smgplugins.syslog.config

import com.smule.smg.core.SMGLoggerApi
import com.typesafe.config.{Config, ConfigFactory}
import play.api.Logger

import java.io.File
import scala.collection.JavaConverters._
import scala.collection.mutable

case class PostProcessorMapLookup(mapFn: String, defaultVal: Option[String]) extends StringPostProcessor {
  private val log = Logger.logger

  private var lookupMap = Map[String, String]()

  private def loadMapFromHocon() : Unit = {
    try {
      val mm = mutable.Map[String,String]()
      val cf: Config = ConfigFactory.parseFile(new File(mapFn))
      cf.entrySet().asScala.foreach{ e =>
        mm.put(e.getKey, e.getValue.toString)
      }
      lookupMap.synchronized { //???
        lookupMap = mm.toMap
      }
    } catch { case t: Throwable =>
      log.error(s"Error while loading lookup map from $mapFn: ${t.getMessage}", t)
    }

  }

  override def process(value: String): String = {
    lookupMap.getOrElse(value, defaultVal.getOrElse(value))
  }

  def reload(): Unit = {
    loadMapFromHocon()
  }
}

object PostProcessorMapLookup {
  def fromYmap(ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[PostProcessorMapLookup] = {
    if (ymap.contains("map_file")) {
      Some(PostProcessorMapLookup(
        mapFn = ymap("map_file").toString,
        defaultVal = ymap.get("default_value").map(_.toString)
      ))
    } else {
      log.error("PostProcessorMapLookup.fromYmap: missing map_file values")
      None
    }
  }
}