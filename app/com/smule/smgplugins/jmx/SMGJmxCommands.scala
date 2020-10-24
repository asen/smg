package com.smule.smgplugins.jmx

import com.smule.smg.core.{CommandResult, CommandResultCustom, CommandResultListDouble, ParentCommandData, SMGCmdException, SMGLoggerApi}
import com.smule.smg.rrd.SMGRrd

object SMGJmxCommands {
  val VALID_COMMANDS = Set("con", "get")
}

class SMGJmxCommands(log: SMGLoggerApi, client: SMGJmxClient) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":jmx $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  def commandConnect(str: String, timeoutSec: Int, parentData: Option[ParentCommandData]): CommandResult = {
    val hostPort = str.split("\\s+")(0)
    val err = client.checkJmxConnection(hostPort)
    if (err.nonEmpty)
      throwOnError("con", str, timeoutSec, err.get)
    CommandResultCustom(s"JMX connection to $hostPort successfull")
  }

  def commandGet(str: String, timeoutSec: Int, parentData: Option[ParentCommandData]): CommandResult = {
    //(:jmx get) 127.0.0.1:9001 java.lang:type=Memory HeapMemoryUsage:used HeapMemoryUsage:committed
    val tokens = str.split("\\s+") // TODO support quotes/spaces?
    if (tokens.length < 3)
      throwOnError("get", str, timeoutSec,
        "Invalid command definition, need at least 3 tokens")
    val hostPort = tokens(0)
    val jmxObj = tokens(1)
    val jmxAttrs = tokens.drop(2)
    val ret = client.fetchJmxValues(hostPort, jmxObj, jmxAttrs)
    CommandResultListDouble(ret, Some(SMGRrd.tssNow))
  }

  def runPluginFetchCommand(cmd: String,
                            timeoutSec: Int,
                            parentData: Option[ParentCommandData]): CommandResult = {
    val arr = cmd.split("\\s+", 2)
    val action = arr(0)
    if (!SMGJmxCommands.VALID_COMMANDS.contains(action)){
      throw new SMGCmdException(cmd, timeoutSec, -1, "", s"Invalid command action: ${action}")
    }
    val paramStr = arr.lift(1).getOrElse("")
    action match {
      case "con" => commandConnect(paramStr, timeoutSec, parentData)
      case "get" => commandGet(paramStr, timeoutSec, parentData)
    }
  }
}
