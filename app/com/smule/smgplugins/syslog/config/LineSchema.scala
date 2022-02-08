package com.smule.smgplugins.syslog.config

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import java.util.TimeZone
import scala.collection.mutable

case class LineSchema(
                       serverId: String,
                       grokPatternsFile: String,
                       grokLinePatterns: Seq[String],
                       tsLookupConf: GrokLookupConf,
                       tsFormat: String,
                       tsFormatNeedsYear: Boolean,
                       tsTimeZone: Option[TimeZone],
                       idLookupConfs: List[GrokLookupConf],
                       idPostProcessors: Seq[StringPostProcessor],
                       valueLookupConfs: List[GrokLookupConf],
                       jsonIgnoreParseErrors: Boolean,
                       logFailedToParse: Boolean,
                       syslogStatsUpdateNumLines: Long,
                       maxCacheCapacity: Int
                     )

object LineSchema {
  def fromYmap(serverId: String, ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[LineSchema] = {
    try {
      val patternsFile = ymap.get("patterns_file").map(_.toString)
      if (patternsFile.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing patterns_file")
        return None
      }
      val linePatterns = ymap.get("line_patterns").map(x => SMGConfigParser.yobjList(x).map(_.toString))
      if (linePatterns.isEmpty || linePatterns.get.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing line_patterns")
        return None
      }
      val tsLookupConf = ymap.get("ts_lookup").flatMap(o => GrokLookupConf.fromYmap(SMGConfigParser.yobjMap(o), log))
      if (tsLookupConf.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing or invalid ts_lookup definition")
        return None
      }
      val tsFormat = ymap.get("ts_format").map(_.toString)
      if (tsFormat.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing ts_format")
        return None
      }
      val tsFormatNeedsYear = ymap.getOrElse("ts_format_needs_year", "false").toString == "true"
      val tsTimeZone = ymap.get("ts_timezone").map { o =>
        TimeZone.getTimeZone(o.toString)
      }
      val idLookupConfs: List[GrokLookupConf] = ymap.get("id_lookups").map { o =>
        val lst = SMGConfigParser.yobjList(o).toList
        lst.map { oo =>
          val mm = SMGConfigParser.yobjMap(oo)
          GrokLookupConf.fromYmap(mm, log).get
        }
      }.getOrElse(List())
      if (idLookupConfs.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing or invalid id_lookups definition")
        return None
      }
      val idPostProcessors: Seq[StringPostProcessor] = ymap.get("id_processors").map { o =>
        val lst = SMGConfigParser.yobjList(o).toSeq
        lst.map { oo =>
          val mm = SMGConfigParser.yobjMap(oo)
          StringPostProcessor.fromYmap(mm, log).get
        }
      }.getOrElse(Seq())
      val valueLookupConfs: List[GrokLookupConf]  = ymap.get("value_lookups").map { o =>
        val lst = SMGConfigParser.yobjList(o).toList
        lst.map { oo =>
          val mm = SMGConfigParser.yobjMap(oo)
          GrokLookupConf.fromYmap(mm, log).get
        }
      }.getOrElse(List())
      if (valueLookupConfs.isEmpty) {
        log.error(s"LineSchema.fromYmap ($serverId): missing or invalid value_lookups definition")
        return None
      }
      val jsonIgnoreParseErrors: Boolean = ymap.getOrElse("json_ignore_parse_errors", "false").toString == "true"
      Some(
        LineSchema(
          serverId = serverId,
          grokPatternsFile = patternsFile.get,
          grokLinePatterns = linePatterns.get,
          tsLookupConf = tsLookupConf.get,
          tsFormat = tsFormat.get,
          tsFormatNeedsYear = tsFormatNeedsYear,
          tsTimeZone = tsTimeZone,
          idLookupConfs = idLookupConfs,
          idPostProcessors = idPostProcessors,
          valueLookupConfs = valueLookupConfs,
          jsonIgnoreParseErrors = jsonIgnoreParseErrors,
          logFailedToParse = ymap.getOrElse("log_parse_errors", "true").toString == "true",
          syslogStatsUpdateNumLines = ymap.getOrElse("stats_update_num_lines", "1000").toString.toLong,
          maxCacheCapacity = ymap.getOrElse("max_capacity", "1000000").toString.toInt
        )
      )
    } catch { case t: Throwable =>
      log.ex(t, s"LineSchema.fromYmap: unexpected error: ${t.getMessage}")
      None
    }
  }
}