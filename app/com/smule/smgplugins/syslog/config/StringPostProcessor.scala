package com.smule.smgplugins.syslog.config

import com.smule.smg.core.SMGLoggerApi

import scala.collection.mutable
import scala.util.Try

trait StringPostProcessor {
  def process(value: String): String

  def reload(): Unit
}

object StringPostProcessor {
  object ProcessorType extends Enumeration {
    val MAP_LOOKUP, REGEX_REPLACE = Value
  }

  def fromYmap(ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[StringPostProcessor] = {
    val ptype = Try(ProcessorType.withName(ymap("type").toString)).toOption
    if (ptype.isEmpty){
      log.error("StringPostProcessor.fromYmap: invalid or missing type property")
      None
    } else {
      ptype.get match {
        case ProcessorType.MAP_LOOKUP => PostProcessorMapLookup.fromYmap(ymap, log)
        case ProcessorType.REGEX_REPLACE => PostProcessorRegexReplace.fromYmap(ymap, log)
      }
    }
  }
}