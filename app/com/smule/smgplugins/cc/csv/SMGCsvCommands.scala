package com.smule.smgplugins.cc.csv

import com.smule.smg.core._
import org.apache.commons.csv.{CSVFormat, CSVParser}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

object SMGCsvCommands {
  val VALID_SUB_COMMANDS = Set("parse", "parserx", "get", "pget")
  //:cc csv parse [parse opts] [format]
  //  -d|--delim <char> (default ',')
  //  -nh|--no-header  - csv has no header row
  //  -sh|--strict-header - if set parse will abort on duplicate or missing header column
  //  -h|--headers <hdr1>,<hdr2>,...  - set custom header names based on position
  // [format] (default - DEFAULT) - apache commons csv pre-defined format name (e.g. EXCEL)

  //:cc csv parserx [parse opts]
  //  -d|--delim <regex> (default "\\s+")
  //  -nh|--no-header  - csv has no header row
  //  -sh|--strict-header - if set parse will abort on duplicate or missing header column
  //  -h|--headers <hdr1>,<hdr2>,...  - set custom header names based on position

  //:cc csv get [get opts] <row selectors (k=v)> <val selectors>
  //  -e0|--empty-as-0 - if set non existing/empty values will return "0.0"
  //  -eN|--empty-as-nan - if set non existing/empty values will return "NaN"
  // <row selectors> - col1=val1 col2=val2
  //    col is either a number (0-based column index) or a column header name
  //    val is the value which the row must have in the respective column
  //      if val starts with '~' it will be treated as regex
  //      if val start with '!' the match is inverted (i.e. the row must not have that value)
  //      use !~... for negative regex match
  //      use 0=~.* to match any row
  // <val selectors> - list of zero-based column indexes or column header names
  // In both cases if a column header name is a number it can be "quoted" in the definition so
  // that it is not treated as column index

  //:cc csv pget [get opts]
  // parse the csv using default parse options and get value using provided get options in one shot
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

    def dump(delim: String = ","): String = ((if (headers.nonEmpty) {
      Seq(headers.toSeq.sortBy(_._2).map(_._1).mkString(delim))
    } else Seq[String]()) ++ rows.map(_.mkString(delim))).mkString("\n")
  }

  private case class MyParseOpts(
                                  var firstRowIsHdr: Boolean = true,
                                  var strictHdr: Boolean = false,
                                  var delimOpt: Option[String] = None,
                                  var headersOpt: Option[Array[String]] = None,
                                  var params: Seq[String] = Seq()
  )

  private def getMyParseOpts(paramStr: String): MyParseOpts = {
    val ret = MyParseOpts()
    var params = paramStr.split("\\s+").toList
    while (params.nonEmpty && params.head.startsWith("-")){
      val opt = params.head
      opt match {
        case "-nh" | "--no-header" => ret.firstRowIsHdr = false
        case "-sh" | "--strict-header" => ret.strictHdr = true
        case "-h" | "--headers" => {
          params = params.tail
          val h = params.headOption.getOrElse("").split(ret.delimOpt.getOrElse(","))
          ret.headersOpt = Some(h)
        }
        case "-d" | "--delim" => {
          params = params.tail
          val d = params.headOption.getOrElse(",")
          ret.delimOpt = Some(d)
        }
        case _ => log.warn(s"Invalid csv parse option (ignored): $opt")
      }
      if (params.nonEmpty)
        params = params.tail
    }
    ret.params = params
    ret
  }

  private def myParse(inp: String, paramStr: String): CSVParsedData = {
    val parseOpts = getMyParseOpts(paramStr)
    val formatStr = parseOpts.params.headOption.getOrElse("")
    var format = if (formatStr.isBlank)
      CSVFormat.DEFAULT
    else
      Try(CSVFormat.valueOf(formatStr.strip())).getOrElse(CSVFormat.DEFAULT)
    if (parseOpts.headersOpt.isDefined){
      format = format.withHeader(parseOpts.headersOpt.get:_*)
    } else {
      if (parseOpts.firstRowIsHdr)
        format = format.withFirstRecordAsHeader().withSkipHeaderRecord()
      if (!parseOpts.strictHdr)
        format = format.withAllowMissingColumnNames().withAllowDuplicateHeaderNames()
    }
    if (parseOpts.delimOpt.isDefined) {
      val charDelim = if (parseOpts.delimOpt.get.lengthCompare(1) > 0){
        log.warn(s"CSV parse command will only use the first character from the supplied delimiter: ${parseOpts.delimOpt.get}")
        parseOpts.delimOpt.get(0)
      } else if (parseOpts.delimOpt.get.lengthCompare(1) < 0) {
        log.warn("CSV parse command with empty delimiter, will use the default")
        ','
      } else parseOpts.delimOpt.get(0)
      format = format.withDelimiter(charDelim)
    }
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

  private def myParseRx(inp: String, paramStr: String): CSVParsedData = {
    val parseOpts = getMyParseOpts(paramStr)
    val myDelim = parseOpts.delimOpt.getOrElse("\\s+")
    var lines = inp.split('\n').filter(_.nonEmpty) //TODO make stripping empty lines an option
    val hdrs: Array[String] = if (parseOpts.headersOpt.isDefined)
      parseOpts.headersOpt.get
    else if (parseOpts.firstRowIsHdr) {
      val h = lines.headOption.getOrElse("").split(myDelim)
      if (lines.nonEmpty)
        lines = lines.tail
      h
    } else if (parseOpts.strictHdr){
      throw new RuntimeException("Strict headers requested but no header row")
    } else Array[String]()
    val ret = ListBuffer[Array[String]]()
    lines.foreach { ln =>
      ret += ln.split(myDelim)
    }
    CSVParsedData(
      headers = hdrs.zipWithIndex.toMap,
      rows = ret.toArray
    )
  }

  private def csvParseRx(paramStr: String, timeoutSec: Int,
                         parentData: ParentCommandData): CommandResult = {
    try {
      val data = myParseRx(parentData.res.asStr, paramStr)
      CommandResultCustom(data)
    } catch { case t: Throwable =>
      throwOnError("parserx", paramStr, timeoutSec, s"CSV Parse error: ${t.getMessage}")
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
      case "parserx" => csvParseRx(rem, timeoutSec, parentData.get)
      case "get" => csvGet(rem, timeoutSec, parentData.get)
      case "pget" => csvParseAndGet(rem, timeoutSec, parentData.get)
      case _ => throwOnError(subCmd, rem, timeoutSec,
        s"Config error: SMGCsvCommands: Invalid sub-command: $subCmd")
    }
  }
}
