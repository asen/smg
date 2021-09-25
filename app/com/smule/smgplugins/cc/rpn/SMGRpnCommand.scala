package com.smule.smgplugins.cc.rpn

import com.smule.smg.core.{CommandResult, CommandResultListDouble, ParentCommandData, SMGCmdException, SMGLoggerApi}
import com.smule.smg.rrd.SMGRrd
import com.smule.smgplugins.cc.shared.SMGCCRunner

import scala.collection.mutable.ListBuffer

//:cc rpn <expr1> <expr2...>
// treat input as update data (list of Doubles) and compute RPN expressions from them
// input ines are mapped to $dsX values in the expression where X is the zero-based
// index in the list. Output one result (Double) per RPN expression provided
class SMGRpnCommand(log: SMGLoggerApi) extends SMGCCRunner {

  //:cc rpn (scur.to_f * 100.0) / limit.to_f
  //:cc rpn "$ds1,$ds0,100,*,/"
  def runCommand(action: String, paramStr: String, timeoutSec: Int,
                 parentData: Option[ParentCommandData]): CommandResult = {
    try {
      val expressions = paramStr.split("\\s+").filterNot(_.isBlank)
      val data = parentData.get.res.asUpdateData(0)
      val ret = expressions.map { expr =>
        SMGRrd.computeRpnValue(expr, data.values)
      }
      CommandResultListDouble(ret.toList, None)
    } catch { case t: Throwable =>
      throwOnError(action, paramStr, timeoutSec, s"Error from RPN expression: ${t.getMessage}")
    }
  }
}
