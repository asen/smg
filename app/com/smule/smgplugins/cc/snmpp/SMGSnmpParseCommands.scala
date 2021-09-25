package com.smule.smgplugins.cc.snmpp

import com.smule.smg.core._
import com.smule.smgplugins.cc.shared.{CCStringUtil, SMGCCRunner}

object SMGSnmpParseCommands {
  val VALID_SUB_COMMANDS = Set("parse", "get", "pget")
  //:cc snmpp parse [parse opts]
  //  -l|--long-keys - keep the full SNMP OID key value (normally the part until the first :: is stripped)
  //:cc snmpp get [get opts] <key1> <key2>
  //  -d|--default <val> - return the supplied default value if a keyX is not found
  //  missing key with no default value provided will result in error
  //:cc snmpp pget [get opts] <key1> <key2>
  //  parse and get in one shot, no parse options supported
}


// $ snmpget -v2c -cpublic -mall 127.0.0.1 laLoad.1 laLoad.2 ssCpuRawUser.0 ssCpuRawNice.0 ssCpuRawSystem.0 \
//    ssCpuRawIdle.0 ssCpuRawWait.0 ssCpuRawInterrupt.0 ssCpuRawSoftIRQ.0 ssRawInterrupts.0 ssRawContexts.0 \
//    ssIORawSent.0 ssIORawReceived.0 ssSwapIn.0 ssSwapOut.0 ifHCInOctets.2 ifHCOutOctets.2 tcpPassiveOpens.0 \
//    tcpActiveOpens.0 tcpCurrEstab.0
//UCD-SNMP-MIB::laLoad.1 = STRING: 8.51
//UCD-SNMP-MIB::laLoad.2 = STRING: 7.97
//UCD-SNMP-MIB::ssCpuRawUser.0 = Counter32: 577273246
//UCD-SNMP-MIB::ssCpuRawNice.0 = Counter32: 468602
//UCD-SNMP-MIB::ssCpuRawSystem.0 = Counter32: 3470359369
//UCD-SNMP-MIB::ssCpuRawIdle.0 = Counter32: 1079026339
//UCD-SNMP-MIB::ssCpuRawWait.0 = Counter32: 18759281
//UCD-SNMP-MIB::ssCpuRawInterrupt.0 = Counter32: 0
//UCD-SNMP-MIB::ssCpuRawSoftIRQ.0 = Counter32: 22616433
//UCD-SNMP-MIB::ssRawInterrupts.0 = Counter32: 905777535
//UCD-SNMP-MIB::ssRawContexts.0 = Counter32: 3215916578
//UCD-SNMP-MIB::ssIORawSent.0 = Counter32: 2763758984
//UCD-SNMP-MIB::ssIORawReceived.0 = Counter32: 2558642878
//UCD-SNMP-MIB::ssSwapIn.0 = INTEGER: 0 kB
//UCD-SNMP-MIB::ssSwapOut.0 = INTEGER: 0 kB
//IF-MIB::ifHCInOctets.2 = Counter64: 36079743333847
//IF-MIB::ifHCOutOctets.2 = Counter64: 4279644472991
//TCP-MIB::tcpPassiveOpens.0 = Counter32: 1536125
//TCP-MIB::tcpActiveOpens.0 = Counter32: 255737619
//TCP-MIB::tcpCurrEstab.0 = Gauge32: 414

class SMGSnmpParseCommands(log: SMGLoggerApi) extends SMGCCRunner {
  
  case class SNMPParsedData(data: Map[String,String]) {
    def getValues(keys: Seq[String], default: Option[String], paramStr: String): List[String] = {
      keys.map { key =>
        if (data.contains(key)){
          data(key)
        } else {
          if (default.isDefined)
            default.get
          else
            throwOnError("get", paramStr, 0,
              s"SNMPParsedData: Key not found: $key, data=$data")
        }
      }.toList
    }
  }

  private def myParse(parentRes: CommandResult, paramStr: String): SNMPParsedData = {
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    var longKeys: Boolean = false
    var params = paramStr.split("\\s+").toList
    while (params.nonEmpty && params.head.startsWith("-")){
      val opt = params.head
      opt match {
        case "-l" | "--long-keys" => longKeys = true
        case _ => log.warn(s"Invalid csv parse option (ignored): $opt")
      }
      if (params.nonEmpty)
        params = params.tail
    }
    val kvs = inpLines.flatMap { ln =>
      val arr = ln.split("\\s*=\\s*", 2)
      arr.lift(1).map { vs =>
        val v = if (vs.contains(":")){ // strip value type
          vs.split("\\s*:\\s*", 2).lift(1).getOrElse("")
        } else vs
        val k = if (longKeys)
          arr(0)
        else {
          val arr0 = arr(0).split("::", 2)
          arr0.lift(1).getOrElse(arr0(0))
        }
        (k, CCStringUtil.normalizeV(v))
      }
    }.toMap
    SNMPParsedData(kvs)
  }

  private def snmpParse(paramStr: String, timeoutSec: Int,
                       parentData: ParentCommandData): CommandResult = {
    try {
      val data = myParse(parentData.res, paramStr)
      CommandResultCustom(data)
    } catch { case t: Throwable =>
      throwOnError("parse", paramStr, timeoutSec, s"CSV Parse error: ${t.getMessage}")
    }
  }

  private def snmpGet(paramStr: String, timeoutSec: Int,
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
      val parsedData = parentCommandResult.data.asInstanceOf[SNMPParsedData]
      val ret = parsedData.getValues(tkns, default, paramStr)
      CommandResultListString(ret, None)
    } catch {
      case c: SMGCmdException => throw c
      case t: Throwable =>
        throwOnError("parse", paramStr, timeoutSec, s"CSV Get error: ${t.getMessage}")
    }
  }

  private def snmpParseAndGet(paramStr: String, timeoutSec: Int,
                             parentData: ParentCommandData): CommandResult = {
    val parsedResult = snmpParse("", timeoutSec, parentData) // TODO only supporting short keys in pget
    snmpGet(paramStr, timeoutSec, parsedResult)
  }

  def runCommand(action: String, paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError("", paramStr, timeoutSec, s"SNMP commands expect parent data")
    }
    val arr = paramStr.stripLeading().split("\\s+", 2)
    val subCmd = arr(0)
    val rem = arr.lift(1).getOrElse("")
    if (!SMGSnmpParseCommands.VALID_SUB_COMMANDS.contains(subCmd))
      throwOnError(subCmd, rem, timeoutSec,
        s"SMGSnmpParseCommands: Invalid snmpp sub-command - must be one of: ${SMGSnmpParseCommands.VALID_SUB_COMMANDS.mkString(",")}")
    subCmd match {
      case "parse" => snmpParse(rem, timeoutSec, parentData.get)
      case "get" => snmpGet(rem, timeoutSec, parentData.get.res)
      case "pget" => snmpParseAndGet(rem, timeoutSec, parentData.get)
      case _ => throwOnError(subCmd, rem, timeoutSec,
        s"Config error: SMGSnmpParseCommands: Invalid sub-command: $subCmd")
    }
  }
}
