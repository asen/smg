package com.smule.smgplugins.scrape

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.immutable.StringOps
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

case class OpenMetricsStat(
                          metaKey: Option[String],
                          metaType: Option[String],
                          metaHelp: Option[String],
                          name: String,
                          labels: Seq[(String, String)],
                          value: Double,
                          tsms: Option[Long]
                          ) {
  private lazy val normalizedUid: String = name + (if (labels.nonEmpty){
    "." + labels.map(t => s"${t._1}.${t._2}").mkString(".")
  } else "")

  lazy val safeUid: String = normalizedUid.replaceAll(
    "[^" + SMGConfigParser.ALLOWED_UID_CHARS_REGEX_STR + "]", "_")

  lazy val title: String = name + (if (labels.nonEmpty) {
    " " + labels.map(t => s"${t._1}=${t._2}").mkString(" ") } else "")
}

object OpenMetricsStat {

  private val START_LABELS = '{'
  private val END_LABELS = '}'
  private val END_OF_LABEL_REGEX: Regex = "[\\s,}]".r

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
                         log: SMGLoggerApi
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
      val value = arr(0).toDouble
      val tsms = arr.lift(1).map(_.toLong)
      Some(
        OpenMetricsStat(
          metaKey = metaKey,
          metaType = metaType,
          metaHelp = metaHelp,
          name = name,
          labels = labels,
          value = value,
          tsms = tsms
        )
      )
    } catch { case t: Throwable =>
      log.ex(t, s"OpenMetricsStat.parseLine: bad line (unexpected error): $ln")
      None
    }
  }

  def parseText(inp: String, log: SMGLoggerApi): Seq[OpenMetricsStat] = {
    val ret = ListBuffer[OpenMetricsStat]()
    var curMetaKey: Option[String] = None
    var curMetaType: Option[String] = None
    var curMetaHelp: Option[String] = None
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
          }
        } else { // parse line
          val pOpt = parseLine(ln, curMetaKey, curMetaType, curMetaHelp, log)
          if (pOpt.isDefined) ret += pOpt.get
        }
      } // else - empty line ... TODO - should we reset curMetaKey?
    }
    ret.toList
  }
}