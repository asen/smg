package com.smule.smgplugins.scrape

import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smgplugins.scrape.SMGScrapeCommands.{FETCH_OPTION_BEARER_TOKEN_FILE, FETCH_OPTION_SECURE_TLS, PARSE_OPTION_LABEL_UIDS}

object SMGScrapeCommands {
  val VALID_COMMANDS = Set("fetch", "http", "parse", "get")
  val PARSE_OPTION_LABEL_UIDS = ":lbluid"

  val FETCH_OPTION_SECURE_TLS = ":secure"
  val FETCH_OPTION_BEARER_TOKEN_FILE = ":tokenf"
}

class SMGScrapeCommands(log: SMGLoggerApi) {

  private def parseText(inp: String, labelUid: Boolean): CommandResult = {
    val stats  = OpenMetricsStat.parseText(inp, log, labelsInUid = labelUid)
    CommandResultCustom(OpenMetricsResultData(stats))
  }

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":scrape $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  private val httpClient = new ScrapeHttpClient(log)

  private def commandFetchCommon(paramStr: String,
                           timeoutSec: Int,
                           parentData: Option[ParentCommandData]): (String, Boolean) = {
    var myParamStr = paramStr
    var secureTls = false
    var tokenFile: Option[String] = None
    var labelUids: Boolean = false
    while (myParamStr.startsWith(":")){
      val arr = myParamStr.split("\\s+", 2)
      arr(0) match {
        case FETCH_OPTION_SECURE_TLS => {
          secureTls = true
          myParamStr = arr.lift(1).getOrElse("")
        }
        case FETCH_OPTION_BEARER_TOKEN_FILE => {
          val arr1 = arr.lift(1).getOrElse("").split("\\s+", 2)
          tokenFile = Some(arr1(0))
          myParamStr = arr1.lift(1).getOrElse("")
        }
        case PARSE_OPTION_LABEL_UIDS => {
          labelUids = true
          myParamStr = arr.lift(1).getOrElse("")
        }
        case x => throwOnError("fetch", paramStr,
          timeoutSec, s"Invalid fetch option param: $x")
      }
    }
    val targetUrl = myParamStr.strip()
    if (targetUrl.isBlank)
      throwOnError("fetch", paramStr,
        timeoutSec, s"Invalid fetch url param - blank")
    (httpClient.getUrl(targetUrl, timeoutSec, secureTls, tokenFile), labelUids)
  }

  private def commandFetchAndParse(paramStr: String,
                                   timeoutSec: Int,
                                   parentData: Option[ParentCommandData]): CommandResult = {
    val (dataTxt, labelUids) = commandFetchCommon(paramStr, timeoutSec, parentData)
    try {
      parseText(dataTxt, labelUids)
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec,
        s"Unexpected OpenMetrics parse error: ${t.getClass.getName}: ${t.getMessage}")
    }
  }

  private def commandFetchOnly(paramStr: String,
                               timeoutSec: Int,
                               parentData: Option[ParentCommandData]): CommandResult = {
    val (dataTxt, labelUids) = commandFetchCommon(paramStr, timeoutSec, parentData)
    CommandResultCustom(dataTxt)
  }

  private def commandParse(paramStr: String,
                          timeoutSec: Int,
                          parentData: Option[ParentCommandData]): CommandResult = {
    var myParamStr = paramStr
    var labelUids: Boolean = false
    if (myParamStr.startsWith(SMGScrapeCommands.PARSE_OPTION_LABEL_UIDS)){
      labelUids = true
      myParamStr = myParamStr.stripPrefix(SMGScrapeCommands.PARSE_OPTION_LABEL_UIDS).stripLeading()
    }
    val dataTxt = if ((myParamStr == "") || (myParamStr == "-")){
      // expecting data from parent
      if (parentData.isEmpty)
        throwOnError("parse", myParamStr, timeoutSec, "Did not get parentData to parse")
      parentData.get.res.asStr
    } else {
      try {
        SMGFileUtil.getFileContents(myParamStr.strip())
      } catch { case t: Throwable =>
        throwOnError("parse", paramStr, timeoutSec, s"Could not read file: $myParamStr: ${t.getMessage}")
      }
    }
    try {
      parseText(dataTxt, labelUids)
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec,
        s"Unexpected OpenMetrics parse error: ${t.getClass.getName}: ${t.getMessage}")
    }
  }

  private def commandGet(paramStr: String,
                        timeoutSec: Int,
                        parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty)
      throwOnError("get", paramStr, timeoutSec, "Did not get parsed parentData to get data from")
    val parsedData = parentData.get.res.data.asInstanceOf[OpenMetricsResultData]
    val keys = paramStr.strip().split("\\s*,\\s*")
    var conflictingTsms = false
    var tsms: Option[Long] = None
    val ret = keys.map { k =>
      val opt = parsedData.byUid.get(k)
      if (opt.isEmpty){
        throwOnError("get", paramStr, timeoutSec, s"Key not found in metrics data: ${k}")
      }
      val newTsms = opt.get.tsms
      if (tsms.isEmpty && !conflictingTsms)
        tsms = newTsms
      else if (tsms != newTsms){
        log.warn(s"SMGScrapeCommands.commandGet: Conflicting timestamps in get: $paramStr")
        conflictingTsms = true
        tsms = None
      }
      opt.get.value
    }
    val mytss = tsms.map(x => (x / 1000).toInt)
    CommandResultListDouble(ret.toList, if (mytss.isDefined) mytss else parentData.flatMap(_.useTss) )
  }

  def runPluginFetchCommand(cmd: String,
                            timeoutSec: Int,
                            parentData: Option[ParentCommandData]): CommandResult = {
    val arr = cmd.split("\\s+", 2)
    val action = arr(0)
    if (!SMGScrapeCommands.VALID_COMMANDS.contains(action)){
      throw new SMGCmdException(cmd, timeoutSec, -1, "", s"Invalid command action: ${action}")
    }
    val paramStr = arr.lift(1).getOrElse("")
    action match {
      case "fetch" => commandFetchAndParse(paramStr, timeoutSec, parentData)
      case "http"  => commandFetchOnly(paramStr, timeoutSec, parentData)
      case "parse" => commandParse(paramStr,timeoutSec, parentData)
      case "get"   => commandGet(paramStr,timeoutSec, parentData)
    }
  }
}
