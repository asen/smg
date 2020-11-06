package com.smule.smgplugins.cc.ln

import com.smule.smg.core._
import com.smule.smgplugins.cc.rx.SMGRegexCommands

class SMGLineCommand(log: SMGLoggerApi) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  // :cc ln -s ", " 1
  def lnCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Line command expects parent data")
    }
    var sepStr = " "
    var rem = paramStr.stripLeading()
    if (rem.startsWith("-s") || rem.startsWith("--separator")){
      val arr = rem.split("\\s+", 2)
      rem = arr.lift(1).getOrElse("")
      val (rxs, newRem) = SMGRegexCommands.delimitedStr(rem)
      sepStr = rxs
      rem = newRem.stripLeading()
    }
    val idxes = SMGRegexCommands.getIntegerSeq(rem)
    if (idxes.isEmpty)
      throwOnError(action, paramStr, timeoutSec,
        s"SMGLineCommands: Invalid command (no indexes specified): args=$rem")
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val maxSplit = idxes.max + 2 // idxes are 1-based, 0 means entire line
    val strLst = inpLines.map { inp =>
      val arr = inp.split(sepStr, maxSplit)
      val out = idxes.flatMap { ix =>
        if (ix == 0)
          Some(inp)
        else
          arr.lift(ix - 1)
      }
      out.mkString(" ")
    }
    CommandResultListString(strLst, None)
  }
}
