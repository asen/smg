package com.smule.smgplugins.cc.kv

import com.smule.smg.core.{CommandResult, CommandResultCustom, CommandResultListString, ParentCommandData, SMGCmdException, SMGLoggerApi}
import com.smule.smgplugins.cc.shared.CCStringUtil

object SMGKvParseCommands {
  val VALID_SUB_COMMANDS = Set("parse", "get", "pget")
  // :cc kv parse [opts]
  //   -d|--delim <str> (default '=')
  //   -n|--normalize (default - false) - convert kb/mb/gb suffixes and strip surrounding whitespace
  // :cc kv get [opts] <key1> <key2...>
  //   -d |--default <str> (default - None) - return the default value if key is not found
  // :cc kv pget [get opts] <key1> <key2...>
}

class SMGKvParseCommands(log: SMGLoggerApi) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  case class KvParsedData(data: Map[String,String]) {
    def getValues(keys: Seq[String], default: Option[String], paramStr: String): List[String] = {
      keys.map { key =>
        if (data.contains(key)){
          data(key)
        } else {
          if (default.isDefined)
            default.get
          else
            throwOnError("get", paramStr, 0,
              s"KvParsedData: Key not found: $key, data=$data")
        }
      }.toList
    }
  }

  private def myParse(parentRes: CommandResult, paramStr: String): KvParsedData = {
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    var params = CCStringUtil.tokenize(paramStr)
    var delim = "="
    var normalize = false
    while (params.nonEmpty && params.head.startsWith("-")){
      val opt = params.head
      opt match {
        case "-d" | "--delim" =>
          params = params.tail
          delim = params.headOption.getOrElse("")
        case "-n" | "--normalize" =>
          normalize = true
        case _ => log.warn(s"Invalid csv parse option (ignored): $opt")
      }
      if (params.nonEmpty)
        params = params.tail
    }
    val kvs = inpLines.flatMap { ln =>
      val arr = ln.split(delim, 2)
      arr.lift(1).map { vs =>
        var v = vs
        var k = arr(0)
        if (normalize) {
          v = CCStringUtil.normalizeV(vs.strip()).strip()
          k = k.strip()
        }
        (k, v)
      }
    }.toMap
    KvParsedData(kvs)
  }

  private def kvParse(paramStr: String, timeoutSec: Int,
                        parentData: ParentCommandData): CommandResult = {
    try {
      val data = myParse(parentData.res, paramStr)
      CommandResultCustom(data)
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec, s"CSV Parse error: ${t.getMessage}")
    }
  }

  private def kvGet(paramStr: String, timeoutSec: Int,
                      parentCommandResult: CommandResult): CommandResult = {
    try {
      var default: Option[String] = None
      var tkns = CCStringUtil.tokenize(paramStr)
      if (tkns.isEmpty)
        throwOnError("get", paramStr, timeoutSec, s"Invalid params (need at least one key) $paramStr")
      if (tkns.head == "-d" || tkns.head == "--default"){
        tkns = tkns.tail
        default = tkns.headOption
        if (tkns.nonEmpty)
          tkns = tkns.tail
      }
      if (tkns.isEmpty)
        throwOnError("get", paramStr, timeoutSec,
          s"Invalid params (need at least one key in addition to default) $paramStr")
      val parsedData = parentCommandResult.data.asInstanceOf[KvParsedData]
      val ret = parsedData.getValues(tkns, default, paramStr)
      CommandResultListString(ret, None)
    } catch {
      case c: SMGCmdException => throw c
      case t: Throwable =>
        throwOnError("parse", paramStr, timeoutSec, s"CSV Get error: ${t.getMessage}")
    }
  }

  private def kvParseAndGet(paramStr: String, timeoutSec: Int,
                              parentData: ParentCommandData): CommandResult = {
    val parsedResult = kvParse("", timeoutSec, parentData) // TODO only supporting k=v in pget
    kvGet(paramStr, timeoutSec, parsedResult)
  }

  def kvParseCommand(action: String, paramStr: String, timeoutSec: Int,
                       parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError("", paramStr, timeoutSec, s"SNMP commands expect parent data")
    }
    val arr = paramStr.stripLeading().split("\\s+", 2)
    val subCmd = arr(0)
    val rem = arr.lift(1).getOrElse("")
    if (!SMGKvParseCommands.VALID_SUB_COMMANDS.contains(subCmd))
      throwOnError(subCmd, rem, timeoutSec,
        s"SMGKvParseCommands: Invalid kv sub-command - must be one of: ${SMGKvParseCommands.VALID_SUB_COMMANDS.mkString(",")}")
    subCmd match {
      case "parse" => kvParse(rem, timeoutSec, parentData.get)
      case "get" => kvGet(rem, timeoutSec, parentData.get.res)
      case "pget" => kvParseAndGet(rem, timeoutSec, parentData.get)
      case _ => throwOnError(subCmd, rem, timeoutSec,
        s"Config error: SMGSnmpParseCommands: Invalid sub-command: $subCmd")
    }
  }
}
