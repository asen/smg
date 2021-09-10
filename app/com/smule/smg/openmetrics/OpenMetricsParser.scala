package com.smule.smg.openmetrics

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.immutable.StringOps
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

object OpenMetricsParser {

  private val START_LABELS = '{'
  private val END_LABELS = '}'
  private val END_OF_LABEL_REGEX: Regex = "[\\s,}]".r

  private def normalizedUid(name: String, labels: Seq[(String, String)]): String =
    name + (if (labels.nonEmpty){
      "." + labels.map(t => s"${t._1}.${t._2}").mkString(".")
    } else "")

  private val replaceRegexStr = "[^" + SMGConfigParser.ALLOWED_UID_CHARS_REGEX_STR + "]"

  def safeUid(in: String): String = in.replaceAll(replaceRegexStr, "_")

  def labelUid(name: String, labels: Seq[(String, String)]): String =
    safeUid(normalizedUid(name, labels))

  def groupIndexUid(name: String, groupIndex: Option[Int], suffixPrefix: String = "_"): String = {
    val safeName = safeUid(name)
    if (groupIndex.isEmpty) safeName else
      safeName + s".${suffixPrefix}${groupIndex.get}"
  }

  def mergeLabels(in: Seq[(String,String)]): Map[String,Seq[String]] = {
    in.groupBy(_._1).map { case (k, vs) =>
      (k, vs.map(_._2).distinct)
    }
  }

  // extract a quoted value out of inp and return it together with the remaining string
  // assumes input starts with a single or double quote
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
        log.get.ex(t, s"OpenMetricsGroup.parseLabels: bad input: $inp")
      (Seq(), "")
    }
  }

  private def parseLine(
                         ln: String,
                         log: Option[SMGLoggerApi]
                       ): Option[OpenMetricsRow] = {
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
          log.get.warn(s"OpenMetricsGroup.parseLine: bad line (not enough tokens or corrupt labels): ${ln.take(2000)}")
        return None
      }
      val arr = rem.split("\\s+")
      if (arr.length > 2) {
        if (log.isDefined)
          log.get.warn(s"OpenMetricsGroup.parseLine: bad line (too many tokens): ${ln.take(2000)}")
      }
      // some special cases for Go NaN formatting
      val value = if (arr(0).matches("^[\\+-]?Inf.*") )
        Double.NaN
      else try {
        arr(0).toDouble
      } catch { case t: Throwable =>
        if (log.isDefined)
          log.get.warn(s"OpenMetricsGroup.parseLine: bad line (invalid Double value): ${arr(0)}: ln=${ln.take(2000)}")
        return None
      }
      var tsms = arr.lift(1).map(_.toLong)
      if (tsms.isDefined && tsms.get <= 0){
        if (log.isDefined)
          log.get.warn(s"OpenMetricsGroup.parseLine: bad line (non-positive timestamp value): ${arr(1)}: ln=${ln.take(2000)}")
        tsms = None
      }
      Some(
        OpenMetricsRow(
          name = name,
          labels = labels,
          value = value,
          tsms = tsms
        )
      )
    } catch { case t: Throwable =>
      if (log.isDefined)
        log.get.ex(t, s"OpenMetricsGroup.parseLine: bad line (unexpected error): ${ln.take(10000)}")
      None
    }
  }

  private def parseLinesGroup(
                               lines: Seq[String],
                               metaKey: Option[String],
                               metaType: Option[String],
                               metaHelp: Option[String],
                               log: Option[SMGLoggerApi]
                             ): OpenMetricsGroup = {
    val rows = lines.flatMap { ln =>
      parseLine(ln, log)
    }
    // handle duplicate normalized ids, e.g. with vitess stats one can see:
    //    vtgate_vttablet_call_error_count{db_type="replica",...,shard_name="cc-]"} 1
    //    vtgate_vttablet_call_error_count{db_type="replica",...,shard_name="cc-}"} 1
    // these need consistent deduplication, for now relying on consitent ordering and indexes for that
    rows.groupBy(_.labelUid).foreach { case (_, dups) =>
      if (dups.lengthCompare(1) > 0) {
        dups.zipWithIndex.foreach { case (row, ix) =>
          row.setDupIndex(ix)
        }
      }
    }
    OpenMetricsGroup(metaKey, metaType, metaHelp, rows)
  }

  def parseText(inp: String, log: Option[SMGLoggerApi]): Seq[OpenMetricsGroup] = {
    val ret = ListBuffer[OpenMetricsGroup]()
    var curMetaKey: Option[String] = None
    var curMetaType: Option[String] = None
    var curMetaHelp: Option[String] = None
    val curGroupLines: ListBuffer[String] = ListBuffer[String]()
    val inpRef: StringOps = inp // XXX workaround for Java 11 String.lines() overriding Scala

    def processCurGroupLinesAndReset(): Unit = {
      val grp = parseLinesGroup(curGroupLines,  curMetaKey, curMetaType, curMetaHelp, log)
      if (!grp.isEmpty) {
        ret += grp
      }
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
    //    if (curGroupLines.nonEmpty)
    processCurGroupLinesAndReset()
    ret.toList
  }

  def dumpStats(groups: Seq[OpenMetricsGroup]): List[String] = {
    val ret = ListBuffer[String]()
    var curMetaKey: Option[String] = None
    groups.foreach { stat =>
      if (stat.metaKey != curMetaKey) {
        if (stat.metaKey.isDefined) {
          if (stat.metaHelp.isDefined)
            ret += s"# HELP ${stat.metaKey.get} ${stat.metaHelp.get}"
          if (stat.metaType.isDefined)
            ret += s"# TYPE ${stat.metaKey.get} ${stat.metaType.get}"
        }
      }
      curMetaKey = stat.metaKey
      stat.rows.foreach { row =>
        val labelsStr = if (row.labels.isEmpty) "" else {
          s"${START_LABELS}" + row.labels.map { case (k, v) =>
            k + "=\"" + v + "\""
          }.mkString(",") + END_LABELS
        }
        val tsStr = if (row.tsms.isEmpty) "" else {
          " " + row.tsms.get.toString
        }
        val statStr = row.name + labelsStr + s" ${row.value}" + tsStr
        ret += statStr
      }
    }
    ret.toList
  }
}
