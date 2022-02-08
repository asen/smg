package com.smule.smgplugins.syslog.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.smule.smg.config.SMGStringUtils
import com.smule.smg.core.SMGLoggerApi
import com.smule.smgplugins.syslog.config.{GrokLookupConf, LineSchema}
import com.smule.smgplugins.syslog.shared.{LineData, LineParser}
import io.krakens.grok.api.{Grok, GrokCompiler}
import play.libs.Json

import java.io.{BufferedReader, FileReader, Reader}
import java.text.SimpleDateFormat
import java.time.Year
import java.util
import java.util.TimeZone
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

class GrokParser(override val schema: LineSchema, log: SMGLoggerApi) extends LineParser {
  val serverId: String = schema.serverId

  private val grokCompiler: GrokCompiler = GrokCompiler.newInstance()
  grokCompiler.registerDefaultPatterns()

  private def loadPatterns(): Unit = {
    var patternReader: Reader = null
    try {
      patternReader = new BufferedReader(new FileReader(schema.grokPatternsFile))
      grokCompiler.register(patternReader)
    } catch {
      case t: Throwable => log.error(s"GrokParser($serverId): Unexpected error: " + t.getMessage, t)
    } finally {
      if (patternReader != null) Try(patternReader.close())
    }
  }
  loadPatterns()

  private val groks: Seq[Grok] = schema.grokLinePatterns.map(s => grokCompiler.compile(s"%{$s}"))

  log.info(s"Created GrokParser for serverId=${schema.serverId} " +
    s"groks=${schema.grokLinePatterns.mkString(",")}")

  private def parseDateLong(fmt: String, strVal: String, needsYear: Boolean, tzone: Option[TimeZone]): Long = {
    val (myFmt, myVal) = if (needsYear){
      (s"yyyy ${fmt}", s"${Year.now().getValue} ${strVal}")
    } else (fmt, strVal)
    val sdf = new SimpleDateFormat(myFmt)
    if (tzone.isDefined)
      sdf.setTimeZone(tzone.get)
    sdf.parse(myVal).getTime
  }

  private def getMatchMap(grok: Grok, ln: String): Option[util.Map[String, Object]] ={
    val m = grok.`match`(ln)
    if (!m.isNull)
      Some(m.capture().asInstanceOf[util.Map[String, Object]])
    else
      None
  }

  private def mapFirst[A <: Iterable[T1],T1,T2](src: A, mapFunc: (T1) => Option[T2] ): Option[T2] = {
    val it = src.iterator
    var ret: Option[T2] = None
    while (ret.isEmpty && it.hasNext){
      val cur = it.next()
      ret = mapFunc(cur)
    }
    ret
  }

  private def jsonLookup(jsonPath: Seq[String], jsonNode: JsonNode): Option[Any] = {
    // trying to be null-safe
    var cur: JsonNode = jsonNode
    jsonPath.foreach { jsKey =>
      if (cur.isArray) {
        cur = cur.get(jsKey.toInt)
      } else if (cur.isContainerNode) {
        cur = cur.get(jsKey)
      }
    }
    cur.getNodeType match {
//      case JsonNodeType.ARRAY =>
//      case JsonNodeType.BINARY =>
//      case JsonNodeType.BOOLEAN =>
//      case JsonNodeType.MISSING =>
//      case JsonNodeType.NULL =>
      case JsonNodeType.NUMBER => Some(cur.asDouble())
//      case JsonNodeType.OBJECT =>
//      case JsonNodeType.POJO =>
      case JsonNodeType.STRING => Some(cur.asText())
      case x =>
        log.warn(s"GrokParser(${schema.serverId}).jsonLookup: invalid JsonNodeType - $x")
        None
    }
  }

  private def getGrokValue(
                            grokMap: util.Map[String, Object],
                            lookupName: GrokLookupConf,
                            jsonCache: mutable.Map[String, JsonNode]
                      ): Option[Any] = {
    val grokObjOpt = mapFirst(lookupName.grokNames, { gnm: String =>
      val ret = Option(grokMap.get(gnm))
      val retObj: Option[Object] = if (ret.isDefined) {
        ret.get match {
          case objects: util.ArrayList[Object] @unchecked => objects.asScala.find(x => x != null)
          case _ => ret
        }
      } else ret
      retObj.map(x => (x, gnm))
    })
    grokObjOpt.flatMap { case (myObj, gnm) =>
      if (lookupName.isJson) {
        val parsed: JsonNode = jsonCache.getOrElseUpdate(gnm, {
          try {
            Json.parse(myObj.toString)
          } catch { case t: Throwable =>
            if (schema.jsonIgnoreParseErrors)
              Json.parse("{}")
            else
              throw t
          }
        })
        jsonLookup(lookupName.jsonPath, parsed)
      } else Some(myObj) //.map(_._1)
    }
  }

  private def parseDouble(o: Any) : Double = {
    o match {
      case d: Double => d
      case _ => Try(o.toString.toDouble).getOrElse(Double.NaN)
    }
  }

  override def parseData(ln: String): Option[LineData] = {
    try {
      val mmOpt = mapFirst(groks, { g: Grok => getMatchMap(g, ln) })
      mmOpt.flatMap { mm: util.Map[String, Object] =>
        val jsonCache = mutable.Map[String, JsonNode]()
        val tsStrOpt = getGrokValue(mm, schema.tsLookupConf, jsonCache).map(_.toString)
        tsStrOpt.map { tsStr =>
          val tsLong = parseDateLong(schema.tsFormat, tsStr, schema.tsFormatNeedsYear, schema.tsTimeZone)
          val objectIdParts = schema.idLookupConfs.map { lkn =>
            getGrokValue(mm, lkn, jsonCache).map(_.toString).getOrElse("_")
          }
          val values: List[Double] = schema.valueLookupConfs.map { v =>
            val dv = getGrokValue(mm, v, jsonCache).map(parseDouble).getOrElse(Double.NaN)
            dv
          }
          val processedIdParts: List[String] = objectIdParts.map { part =>
            var processedIdPart = part
            schema.idPostProcessors.foreach { pp =>
              processedIdPart = pp.process(processedIdPart)
            }
            SMGStringUtils.safeUid(processedIdPart)
          }
          LineData(tsLong, processedIdParts, values)
        }
      }
    } catch { case t: Throwable =>
      log.error(s"GrokParser.parseData: Unexpected error: ${t.getMessage}", t)
      None
    }
  }
}

