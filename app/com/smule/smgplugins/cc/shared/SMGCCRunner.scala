package com.smule.smgplugins.cc.shared

import com.smule.smg.core.{CommandResult, ParentCommandData, SMGCmdException}

trait SMGCCRunner {

  protected def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String): Nothing = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  def runCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult
}
