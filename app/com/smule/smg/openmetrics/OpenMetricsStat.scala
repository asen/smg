package com.smule.smg.openmetrics

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.immutable.StringOps
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
                          groupIndex: Int
                          ) {
  private lazy val grpIdxTitlePart = if (groupIndex > 0) { s" ($groupIndex)" } else ""
  lazy val title: String = name + grpIdxTitlePart + metaHelp.map(s => s" - ${s}").getOrElse("")
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

  def groupIndexUid(name: String, groupIndex: Int): String =
    name.replaceAll(replaceRegexStr, "_") + s"_$groupIndex"

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

  private def parseLabels(inp: String, log: SMGLoggerApi): (Seq[(String,String)], String) = {
    try {
      val ret = ListBuffer[(String, String)]()
      var remaining = inp.stripLeading()
      var eolIx = remaining.indexOf(END_LABELS)
      while (eolIx > 0){
        val eqIx = remaining.indexOf('=')
        if (eqIx < 0) throw new RuntimeException("label not having equals sign")
        val key = remaining.take(eqIx)
        remaining = remaining.drop(eqIx+1).stripLeading()
        val (value, remVal) = if (remaining.startsWith("\"") || remaining.startsWith("")){
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
      log.ex(t, s"OpenMetricsStat.parseLabels: bad input: $inp")
      (Seq(), "")
    }
  }

  private def parseLine(
                         ln: String,
                         metaKey: Option[String],
                         metaType: Option[String],
                         metaHelp: Option[String],
                         log: SMGLoggerApi,
                         groupIndex: Int,
                         labelsInUid: Boolean
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
        log.warn(s"OpenMetricsStat.parseLine: bad line (not enough tokens or corrupt labels): $ln")
        return None
      }
      val arr = rem.split("\\s+")
      if (arr.length > 2) {
        log.warn(s"OpenMetricsStat.parseLine: bad line (too many tokens): $ln")
      }
      // some special cases for Go NaN formatting
      val value = if (arr(0).matches("^[\\+-]?Inf.*") )
        Double.NaN
      else try {
        arr(0).toDouble
      } catch { case t: Throwable =>
        log.warn(s"OpenMetricsStat.parseLine: bad Double value: ${arr(0)}: ln=$ln")
        Double.NaN
      }
      val tsms = arr.lift(1).map(_.toLong)
      val smgUid = if (!hasLabels) name else {
        if (labelsInUid){
          OpenMetricsStat.labelUid(name, labels)
        } else {
          OpenMetricsStat.groupIndexUid(name, groupIndex)
        }
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
      log.ex(t, s"OpenMetricsStat.parseLine: bad line (unexpected error): $ln")
      None
    }
  }

  def parseText(inp: String, log: SMGLoggerApi, labelsInUid: Boolean): Seq[OpenMetricsStat] = {
    val ret = ListBuffer[OpenMetricsStat]()
    var curMetaKey: Option[String] = None
    var curMetaType: Option[String] = None
    var curMetaHelp: Option[String] = None
    var groupIndex = 0
    val inpRef: StringOps = inp // XXX workaround for Java 11 String.lines() overriding Scala
    inpRef.lines.foreach { rawLine =>
      val ln = rawLine.strip()
      if (!ln.isEmpty) {
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
                  curMetaHelp = None
                  curMetaType = None
                  groupIndex = 0
                }
                curMetaKey = Some(metaKey)
                val metaData = arr1(1)
                if (metaVerb == "HELP") {
                  curMetaHelp = Some(metaData)
                } else if (metaVerb == "TYPE") {
                  curMetaType = Some(metaData)
                }
              } else {
                log.debug(s"Empty $metaVerb metadata (curMetaKey=${curMetaKey.getOrElse("")})")
              }
            } // else - comment ... TODO - should we reset curMetaKey?
          } catch { case t: Throwable =>
            log.ex(t, s"Unexpected error processing comment line: $ln")
            curMetaKey = None
            groupIndex = 0
          }
        } else { // parse line
          val pOpt = parseLine(ln, curMetaKey, curMetaType, curMetaHelp, log, groupIndex, labelsInUid)
          if (pOpt.isDefined) {
            ret += pOpt.get
            groupIndex += 1
          }
        }
      } // else - empty line ... TODO - should we reset curMetaKey?
    }
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
