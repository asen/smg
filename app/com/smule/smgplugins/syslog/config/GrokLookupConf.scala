package com.smule.smgplugins.syslog.config

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.mutable

case class GrokLookupConf(
                           grokNames: Seq[String], // first matching wins
                           jsonPath: Seq[String]
                         ) {
  val isJson: Boolean = jsonPath.nonEmpty
}

object GrokLookupConf {
  def fromYmap(ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[GrokLookupConf] = {
    try {
      val grokNames = if (ymap.contains("grok_names")) {
        SMGConfigParser.yobjList(ymap("grok_names")).map(_.toString)
      } else Seq()
      val jsonPath = if (ymap.contains("json_path")) {
        SMGConfigParser.yobjList(ymap("json_path")).map(_.toString)
      } else Seq()
      if (grokNames.isEmpty) {
        log.error("GrokLookupConf.fromYmap: Invalid GrokLookupConf ymap - empty grok_names list")
        None
      } else {
        Some(
          GrokLookupConf(grokNames = grokNames, jsonPath = jsonPath)
        )
      }
    } catch { case t: Throwable =>
      log.ex(t, "GrokLookupConf: unexpected exception")
      None
    }
  }
}
