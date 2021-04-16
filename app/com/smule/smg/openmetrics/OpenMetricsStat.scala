package com.smule.smg.openmetrics

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.immutable.StringOps
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

case class OpenMetricsStat(
                          smgUid: String,
                          metaKey: Option[String],
                          metaType: Option[String],
                          metaHelp: Option[String],
                          name: String,
                          labels: Seq[(String, String)],
                          value: Double,
                          tsms: Option[Long],
                          groupIndex: Option[Int]
                          ) {
  private lazy val grpIdxTitlePart = if (groupIndex.isDefined) { s" (${groupIndex.get})" } else ""
  def title(prefix: String): String = {
    (if (prefix == "") "" else s"($prefix) ") +
      name + grpIdxTitlePart + metaHelp.map(s => s" - ${s}").getOrElse("")
  }
}

object OpenMetricsStat {

  private val START_LABELS = '{'
  private val END_LABELS = '}'
  private val END_OF_LABEL_REGEX: Regex = "[\\s,}]".r

  private def normalizedUid(name: String, labels: Seq[(String, String)]): String =
    name + (if (labels.nonEmpty){
        "." + labels.map(t => s"${t._1}.${t._2}").mkString(".")
      } else "")

  private val replaceRegexStr = "[^" + SMGConfigParser.ALLOWED_UID_CHARS_REGEX_STR + "]"

  def labelUid(name: String, labels: Seq[(String, String)]): String =
    normalizedUid(name, labels).replaceAll(replaceRegexStr, "_")

  def groupIndexUid(name: String, groupIndex: Option[Int], suffixPrefix: String = "_"): String = {
    if (groupIndex.isEmpty) name else
      name.replaceAll(replaceRegexStr, "_") + s".${suffixPrefix}${groupIndex.get}"
  }

  // extract a quoted value out of inp and return it together with the remaining string
  // assumes inpiut starts with a single or double quote
  private def quotedValue(inp: String): (String, String) = {
    val quoteChar = inp(0)
    val rem = inp.drop(1)
    val eix = rem.indexOf(quoteChar)
    if (eix < 0) throw new RuntimeException(s"unclosed label value quote ($quoteChar)")
    val value = rem.take(eix)
    (value, rem.drop(eix + 1))
  }

  private def parseLabels(inp: String, log: Option[SMGLoggerApi]): (Seq[(String,String)], String) = {
    try {
      val ret = ListBuffer[(String, String)]()
      var remaining = inp.stripLeading()
      var eolIx = remaining.indexOf(END_LABELS)
      while (eolIx > 0){
        val eqIx = remaining.indexOf('=')
        if (eqIx < 0) throw new RuntimeException("label not having equals sign")
        val key = remaining.take(eqIx)
        remaining = remaining.drop(eqIx+1).stripLeading()
        val (value, remVal) = if (remaining.startsWith("\"") || remaining.startsWith("'")){
          quotedValue(remaining)
        } else {
          val evIx = END_OF_LABEL_REGEX.findFirstMatchIn(remaining).map(_.start).get // can throw ...
          (remaining.take(evIx), remaining.drop(evIx))
        }
        // here we have a value and remaining str
        ret += Tuple2(key, value)
        remaining = remVal.stripLeading()
        if (remaining.startsWith(",")){
          remaining = remaining.drop(1).stripLeading()
        }
        eolIx = remaining.indexOf(END_LABELS)
      }
      if (eolIx < 0) throw new RuntimeException(s"labels def not ending with ${END_LABELS}")
      (ret.toList, remaining.drop(1).stripLeading())
    } catch { case t: Throwable =>
      if (log.isDefined)
        log.get.ex(t, s"OpenMetricsStat.parseLabels: bad input: $inp")
      (Seq(), "")
    }
  }

  private class ParseContext(){
    val uidCounts: mutable.Map[String, Int] = mutable.Map[String,Int]()
  }

  private def parseLine(
                         ln: String,
                         metaKey: Option[String],
                         metaType: Option[String],
                         metaHelp: Option[String],
                         log: Option[SMGLoggerApi],
                         groupIndex: Option[Int],
                         labelsInUid: Boolean,
                         parseContext: ParseContext
                       ): Option[OpenMetricsStat] = {
    try {
      val hasLabels = ln.contains(START_LABELS)
      val (name, labels, remaining) = if (hasLabels) {
        val t = ln.splitAt(ln.indexOf(START_LABELS))
        val (lbls, rem) = parseLabels(t._2.drop(1), log)
        (t._1, lbls, rem)
      } else {
        val a = ln.split("\\s+", 2)
        (a(0), Seq[(String, String)](), a.lift(1).getOrElse(""))
      }
      val rem = remaining.stripLeading() // just on case ...
      if (rem == "") {
        if (log.isDefined)
          log.get.warn(s"OpenMetricsStat.parseLine: bad line (not enough tokens or corrupt labels): $ln")
        return None
      }
      val arr = rem.split("\\s+")
      if (arr.length > 2) {
        if (log.isDefined)
          log.get.warn(s"OpenMetricsStat.parseLine: bad line (too many tokens): $ln")
      }
      // some special cases for Go NaN formatting
      val value = if (arr(0).matches("^[\\+-]?Inf.*") )
        Double.NaN
      else try {
        arr(0).toDouble
      } catch { case t: Throwable =>
        if (log.isDefined)
          log.get.warn(s"OpenMetricsStat.parseLine: bad line (invalid Double value): ${arr(0)}: ln=$ln")
        return None
      }
      var tsms = arr.lift(1).map(_.toLong)
      if (tsms.isDefined && tsms.get <= 0){
        if (log.isDefined)
          log.get.warn(s"OpenMetricsStat.parseLine: bad line (non-positive timestamp value): ${arr(1)}: ln=$ln")
        tsms = None
      }
      var smgUid = if (!hasLabels) name else {
        if (labelsInUid){
          OpenMetricsStat.labelUid(name, labels)
        } else {
          OpenMetricsStat.groupIndexUid(name, groupIndex)
        }
      }
      // enforce indexed uids when conflicting, ref. kube-sate-metrics
      if (!parseContext.uidCounts.contains(smgUid)){
        parseContext.uidCounts(smgUid) = 0
      } else {
        parseContext.uidCounts(smgUid) = parseContext.uidCounts(smgUid) + 1
        smgUid =  OpenMetricsStat.groupIndexUid(name, Some(parseContext.uidCounts(smgUid)), "dup")
      }
      Some(
        OpenMetricsStat(
          smgUid = smgUid,
          metaKey = metaKey,
          metaType = metaType,
          metaHelp = metaHelp,
          name = name,
          labels = labels,
          value = value,
          tsms = tsms,
          groupIndex = groupIndex
        )
      )
    } catch { case t: Throwable =>
      if (log.isDefined)
        log.get.ex(t, s"OpenMetricsStat.parseLine: bad line (unexpected error): $ln")
      None
    }
  }

  private def parseLinesGroup(
                               lines: Seq[String],
                               metaKey: Option[String],
                               metaType: Option[String],
                               metaHelp: Option[String],
                               log: Option[SMGLoggerApi],
                               labelsInUid: Boolean,
                               parseContext: ParseContext
                             ): Seq[OpenMetricsStat] = {
    var groupIndex = if (lines.lengthCompare(1) > 0) Some(0) else None
    lines.flatMap { ln =>
      val opt = parseLine(ln, metaKey, metaType, metaHelp, log, groupIndex, labelsInUid, parseContext)
      if (opt.isDefined && groupIndex.isDefined)
        groupIndex = Some(groupIndex.get + 1)
      opt
    }
  }

  def parseText(inp: String, labelsInUid: Boolean, log: Option[SMGLoggerApi]): Seq[OpenMetricsStat] = {
    val ret = ListBuffer[OpenMetricsStat]()
    var curMetaKey: Option[String] = None
    var curMetaType: Option[String] = None
    var curMetaHelp: Option[String] = None
    val curGroupLines: ListBuffer[String] = ListBuffer[String]()
    val inpRef: StringOps = inp // XXX workaround for Java 11 String.lines() overriding Scala
    val parseContext = new ParseContext()
    def processCurGroupLinesAndReset(): Unit = {
      ret ++= parseLinesGroup(curGroupLines,  curMetaKey, curMetaType, curMetaHelp,
        log, labelsInUid, parseContext)
      curGroupLines.clear()
      curMetaHelp = None
      curMetaType = None
    }

    inpRef.lines.foreach { rawLine =>
      val ln = rawLine.strip()
      if (ln.nonEmpty) {
        if (ln.startsWith("#")) {
          try {
            val commentOrMeta = ln.stripPrefix("#").stripLeading()
            val arr = commentOrMeta.split("\\s+", 2)
            val metaVerb = arr(0)
            if ((arr.length > 1) && ((metaVerb == "HELP") || (metaVerb == "TYPE"))) {
              val arr1 = arr(1).split("\\s+", 2)
              if (arr1.length > 1) {
                val metaKey = arr1(0)
                if (!curMetaKey.contains(metaKey)) {
                  processCurGroupLinesAndReset()
                }
                curMetaKey = Some(metaKey)
                val metaData = arr1(1)
                if (metaVerb == "HELP") {
                  curMetaHelp = Some(metaData)
                } else if (metaVerb == "TYPE") {
                  curMetaType = Some(metaData)
                }
              } else {
                if (log.isDefined)
                  log.get.debug(s"Empty $metaVerb metadata (curMetaKey=${curMetaKey.getOrElse("")})")
              }
            } else { //- comment ... reset curMetaKey?
              processCurGroupLinesAndReset()
            }
          } catch { case t: Throwable =>
            if (log.isDefined)
              log.get.ex(t, s"Unexpected error processing comment line: $ln")
            processCurGroupLinesAndReset()
            curMetaKey = None
          }
        } else { // parse line
          curGroupLines += ln
        }
      } // else - empty line ... TODO - should we reset curMetaKey?
    }
    processCurGroupLinesAndReset()
    ret.toList
  }

  def dumpStats(stats: Seq[OpenMetricsStat]): List[String] = {
    val ret = ListBuffer[String]()
    var curMetaKey: Option[String] = None
    stats.foreach { stat =>
      if (stat.metaKey != curMetaKey) {
        if (stat.metaKey.isDefined) {
          if (stat.metaHelp.isDefined)
            ret += s"# HELP ${stat.metaKey.get} ${stat.metaHelp.get}"
          if (stat.metaType.isDefined)
            ret += s"# TYPE ${stat.metaKey.get} ${stat.metaType.get}"
        }
      }
      curMetaKey = stat.metaKey
      val labelsStr = if (stat.labels.isEmpty) "" else {
        s"${START_LABELS}" + stat.labels.map { case (k, v) =>
          k + "=\"" + v + "\""
        }.mkString(",") + END_LABELS
      }
      val tsStr = if (stat.tsms.isEmpty) "" else {
        " " + stat.tsms.get.toString
      }
      val statStr = stat.name + labelsStr + s" ${stat.value}" + tsStr
      ret += statStr
    }
    ret.toList
  }
}
