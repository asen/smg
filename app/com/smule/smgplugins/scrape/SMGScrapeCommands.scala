package com.smule.smgplugins.scrape

import com.smule.smg.core._
import com.smule.smg.openmetrics.OpenMetricsStat
import com.smule.smg.plugin.SMGPluginLogger

object SMGScrapeCommands {
  val VALID_COMMANDS = Set("fetch", "parse", "get")
  val PARSE_OPTION_LABEL_UIDS = ":lbluid"
}

class SMGScrapeCommands(log: SMGPluginLogger) {

  private def parseText(inp: String, labelUid: Boolean): CommandResult = {
    val stats  = OpenMetricsStat.parseText(inp, log, labelsInUid = labelUid)
    val byUid = stats.groupBy(_.smgUid).map { t =>
      if (t._2.lengthCompare(1) > 0) {
        log.warn(s"SMGScrapePlugin.parseText: Non unique normalizedUid: ${t._1} (${t._2.size} entries)")
      }
      (t._1, t._2.head)
    }
    CommandResultCustom(OpenMetricsData(byUid))
  }

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":scrape $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  private def commandFetch(paramStr: String,
                           timeoutSec: Int,
                           parentData: Option[ParentCommandData]): CommandResult = {
    throw new RuntimeException("Not implemented")
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
    val parsedData = parentData.get.res.data.asInstanceOf[OpenMetricsData]
    val keys = paramStr.strip().split("\\s*,\\s*")
    val ret = keys.map { k =>
      val opt = parsedData.byUid.get(k)
      if (opt.isEmpty){
        throwOnError("get", paramStr, timeoutSec, s"Key not found in metrics data: ${k}")
      }
      opt.get.value
    }
    CommandResultListDouble(ret.toList)
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
      case "fetch" => commandFetch(paramStr, timeoutSec, parentData)
      case "parse" => commandParse(paramStr,timeoutSec, parentData)
      case "get"   => commandGet(paramStr,timeoutSec, parentData)
    }
  }
}
