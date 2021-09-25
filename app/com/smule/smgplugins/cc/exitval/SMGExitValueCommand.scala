package com.smule.smgplugins.cc.exitval

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{CommandResult, CommandResultListDouble, ParentCommandData, SMGCmd, SMGCmdException, SMGFetchException, SMGLoggerApi}
import com.smule.smgplugins.cc.shared.SMGCCRunner

class SMGExitValueCommand(log: SMGLoggerApi, cfSvc: SMGConfigService)  extends SMGCCRunner {

  def runCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (paramStr.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Missing exitval command to execute")
    }
    val ret = try {
      cfSvc.runFetchCommand(SMGCmd(paramStr, timeoutSec), parentData)
      0
    } catch {
      case cex: SMGCmdException => cex.exitCode
      case _: SMGFetchException => 256
    }
    CommandResultListDouble(List(ret.toDouble), parentData.flatMap(_.useTss))
  }
}
