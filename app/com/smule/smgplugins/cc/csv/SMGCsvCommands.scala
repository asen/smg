package com.smule.smgplugins.cc.csv

import com.smule.smg.core._
import org.apache.commons.csv.{CSVFormat, CSVParser}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

object SMGCsvCommands {
  val VALID_SUB_COMMANDS = Set("parse", "get", "pget")

}

class SMGCsvCommands(log: SMGLoggerApi) {
  private def throwOnError(subAction: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc csv $subAction $paramStr", timeoutSec, -1, "", errMsg)
  }

  case class CSVParsedData(
                          headers: Map[String,Int],
                          rows: Array[Array[String]]
                          ) {
    def getValues(sel: SMGCsvValueSelector, paramStr: String, emptyValAs: Option[String]): List[String] = {
      val rowSeq = rows.filter { row =>
        sel.rowSelectors.forall { rs =>
          rs.matches(row, headers)
        }
      }
      if (rowSeq.isEmpty) {
        if (emptyValAs.isDefined)
          sel.colSelectors.map { csel =>
            emptyValAs.get
          }
        else
          throwOnError("get", paramStr, 0, "CSV Row not found")
      } else {
        rowSeq.flatMap { row =>
          sel.colSelectors.map { csel =>
            val idx = csel.idx(headers)
            var ret = row.lift(idx).getOrElse("")
            if (ret.isBlank && emptyValAs.isDefined)
              ret = emptyValAs.get
            ret
          }
        }.toList
      }
    }
  }


  private def myParse(inp: String, paramStr: String): CSVParsedData = {
    var firstRowIsHdr = true
    var strictHdr = false
    var delimOpt: Option[Char] = None
    var headersOpt: Option[Array[String]] = None
    var params = paramStr.split("\\s+").toList
    while (params.nonEmpty && params.head.startsWith("-")){
      val opt = params.head
      opt match {
        case "-nh" | "--no-header" => firstRowIsHdr = false
        case "-sh" | "--strict-header" => strictHdr = true
        case "-h" | "--headers" => {
          params = params.tail
          val h = params.headOption.getOrElse("").split(delimOpt.getOrElse(','))
          headersOpt = Some(h)
        }
        case "-d" | "--delim" => {
          params = params.tail
          val d = params.headOption.getOrElse("").lift(0).getOrElse(',')
          delimOpt = Some(d)
        }
        case _ => log.warn(s"Invalid csv parse option (ignored): $opt")
      }
      params = params.tail
    }
    val formatStr = params.headOption.getOrElse("")
    var format = if (formatStr.isBlank)
      CSVFormat.DEFAULT
    else
      Try(CSVFormat.valueOf(formatStr.strip())).getOrElse(CSVFormat.DEFAULT)
    if (headersOpt.isDefined){
      format = format.withHeader(headersOpt.get:_*)
    } else {
      if (firstRowIsHdr)
        format = format.withFirstRecordAsHeader().withSkipHeaderRecord()
      if (!strictHdr)
        format = format.withAllowMissingColumnNames().withAllowDuplicateHeaderNames()
    }
    if (delimOpt.isDefined)
      format = format.withDelimiter(delimOpt.get)
    val parser = CSVParser.parse(inp, format)
    try {
      CSVParsedData(
        headers = Option(parser.getHeaderMap).map(_.asScala.map(x => (x._1, x._2.toInt)).toMap).getOrElse(Map()),
        rows = parser.getRecords.asScala.map(_.iterator().asScala.toArray).toArray
      )
    } finally {
      parser.close()
    }
  }

  private def csvParse(paramStr: String, timeoutSec: Int,
                       parentData: ParentCommandData): CommandResult = {
    try {
      val data = myParse(parentData.res.asStr, paramStr)
      CommandResultCustom(data)
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec, s"CSV Parse error: ${t.getMessage}")
    }
  }

  private def parseGetFlags(inp: String): (Map[String, String], String) = {
    var rem = inp.stripLeading()
    val flags = mutable.Map[String,String]()
    while (rem.startsWith("-")){
      val optArr = rem.split("\\s+", 2)
      flags(optArr(0)) = "" // TODO support param vals?
      rem = optArr.lift(1).getOrElse("").stripLeading()
    }
    (flags.toMap, rem)
  }

  private def getEmptyValAs(flags: Map[String,String]): Option[String] = {
    if (flags.contains("-e0") || flags.contains("--empty-as-0"))
      Some("0.0")
    else if (flags.contains("-eN") || flags.contains("--empty-as-nan"))
      Some("NaN")
    else
      None
  }

  private def csvGet(paramStr: String, timeoutSec: Int,
                     parentData: ParentCommandData): CommandResult = {
    try {
      val flagsT = parseGetFlags(paramStr)
      val rem = flagsT._2
      val emptyValAs = getEmptyValAs(flagsT._1)
      val valueSelector = SMGCsvValueSelector.parse(rem)
      if (valueSelector.isEmpty || valueSelector.get.invalid)
        throwOnError("get", paramStr, timeoutSec, s"Invalid CSV value selector: $rem")
      val parsedData = parentData.res.data.asInstanceOf[CSVParsedData]
      val ret = parsedData.getValues(valueSelector.get, paramStr, emptyValAs)
      CommandResultListString(ret, None)
    } catch {
      case c: SMGCmdException => throw c
      case t: Throwable =>
        throwOnError("parse", paramStr, timeoutSec, s"CSV Get error: ${t.getMessage}")
    }
  }

  private def csvParseAndGet(paramStr: String, timeoutSec: Int,
                             parentData: ParentCommandData): CommandResult = {
    val parsedData = try {
      myParse(parentData.res.asStr, "") // TODO only supporting DEFAULT in pget
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec, s"CSV Parse error: ${t.getMessage}")
    }
    try {
      val flagsT = parseGetFlags(paramStr)
      val rem = flagsT._2
      val emptyValAs = getEmptyValAs(flagsT._1)
      val valueSelector = SMGCsvValueSelector.parse(rem)
      if (valueSelector.isEmpty || valueSelector.get.invalid)
        throwOnError("pget", paramStr, timeoutSec, s"Invalid CSV value selector; $paramStr")
      val ret = parsedData.getValues(valueSelector.get, paramStr, emptyValAs)
      CommandResultListString(ret, None)
    } catch {
      case c: SMGCmdException => throw c
      case t: Throwable =>
        throwOnError("parse", paramStr, timeoutSec, s"CSV Get error: ${t.getMessage}")
    }
  }

  def csvCommand(action: String, paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError("", paramStr, timeoutSec, s"CSV commands expect parent data")
    }
    val arr = paramStr.stripLeading().split("\\s+", 2)
    val subCmd = arr(0)
    val rem = arr.lift(1).getOrElse("")
    if (!SMGCsvCommands.VALID_SUB_COMMANDS.contains(subCmd))
      throwOnError(subCmd, rem, timeoutSec,
        s"SMGCsvCommands: Invalid csv sub-command - must be one of: ${SMGCsvCommands.VALID_SUB_COMMANDS.mkString(",")}")
    subCmd match {
      case "parse" => csvParse(rem, timeoutSec, parentData.get)
      case "get" => csvGet(rem, timeoutSec, parentData.get)
      case "pget" => csvParseAndGet(rem, timeoutSec, parentData.get)
      case _ => throw new RuntimeException(s"BUG: SMGCsvCommands: Invalid sub-cpommand: $subCmd")
    }
  }
}
