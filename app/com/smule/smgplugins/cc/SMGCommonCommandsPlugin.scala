package com.smule.smgplugins.cc

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{CommandResult, ParentCommandData, SMGCmdException}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smgplugins.cc.csv.SMGCsvCommands
import com.smule.smgplugins.cc.exitval.SMGExitValueCommand
import com.smule.smgplugins.cc.kv.SMGKvParseCommands
import com.smule.smgplugins.cc.ln.SMGLineCommand
import com.smule.smgplugins.cc.map.SMGMapCommand
import com.smule.smgplugins.cc.rpn.SMGRpnCommand
import com.smule.smgplugins.cc.rx.SMGRegexCommands
import com.smule.smgplugins.cc.shared.SMGCCRunner
import com.smule.smgplugins.cc.snmpp.SMGSnmpParseCommands
import com.smule.smgplugins.cc.ts.SMGTsCommand

class SMGCommonCommandsPlugin(val pluginId: String,
                              val interval: Int,
                              val pluginConfFile: String,
                              val smgConfSvc: SMGConfigService
                             ) extends SMGPlugin {
  override val showInMenu: Boolean = false

//  private val myEc: ExecutionContext =
//    smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.plugins-shared")

  override val autoRefresh: Boolean = false

  private val log = new SMGPluginLogger(pluginId)

  private val regexCommandRunner = new SMGRegexCommands(log)
  private val lineCommandRunner = new SMGLineCommand(log)
  private val mapCommandRunner = new SMGMapCommand(log)
  private val csvCommandRunner = new SMGCsvCommands(log)
  private val rpnCommandRunner = new SMGRpnCommand(log)
  private val snmpParseCommandRunner = new SMGSnmpParseCommands(log)
  private val kvParseCommandRunner = new SMGKvParseCommands(log)
  private val tsCommandRunner = new SMGTsCommand(log)
  private val exitvalCommandRunner = new SMGExitValueCommand(log, smgConfSvc)

  private val commandsMap: Map[String, SMGCCRunner] = Map(
    "ln" -> lineCommandRunner,
    "map" -> mapCommandRunner,
    "csv" -> csvCommandRunner,
    "rpn" -> rpnCommandRunner,
    "snmpp" -> snmpParseCommandRunner,
    "kv" -> kvParseCommandRunner,
    "exitval" -> exitvalCommandRunner

//    , "rxe" -> regexCommandRunner,
//    "rxel" -> regexCommandRunner,
//    "rxm" -> regexCommandRunner,
//    "rxml" -> regexCommandRunner,
//    "rx_repl" -> regexCommandRunner

//    , "ts" -> tsCommandRunner,
//    "ts_s" -> tsCommandRunner,
//    "ts_ms" -> tsCommandRunner

  ) ++ SMGRegexCommands.VALID_COMMANDS.toSeq.map { c =>
    (c, regexCommandRunner)
  }.toMap ++ SMGTsCommand.VALID_COMMANDS.toSeq.map { c =>
    (c, tsCommandRunner)
  }.toMap

  override def runPluginFetchCommand(cmd: String, timeoutSec: Int,
                                     parentData: Option[ParentCommandData]): CommandResult = {
    val arr = cmd.split("\\s+", 2)
    val action = arr(0)
    val paramStr = arr.lift(1).getOrElse("")
    val cmdOpt = commandsMap.get(action)
    if (cmdOpt.isDefined) {
      cmdOpt.get.runCommand(action, paramStr, timeoutSec, parentData)
    } else {
      throw SMGCmdException(cmd, timeoutSec, -1, "",
        s"SMGCommonCommandsPlugin: Invalid command: $cmd")
    }
  }

}
