package com.smule.smgplugins.cc.ts

import com.smule.smg.core._
import com.smule.smg.rrd.SMGRrd

// calculate relative time in seconds or milliseconds from timestamps
// coming as parent data (list of Doubles)
//
// :cc ts[_ms] [age|ttl|delta|delta_neg,delta_abs]
//
// use ts/ts_s for unix timestamps (in seconds since 1970) or
// ts_ms for Java-style Long timestamp in milliseconds
//
class SMGTsCommand(log: SMGLoggerApi) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  def tsCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Ts command expects parent data")
    }
    val isTsMs = action match {
      case "ts" | "ts_s" => false
      case "ts_ms" => true
      case x => throwOnError(action, paramStr, timeoutSec, s"Invalid ts sub-command - " +
        s"must be one of ts, ts_s or ts_ms: $x")
    }
    if (paramStr.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Missing ts sub-command operation - " +
        s"must be one of age, ttl, delta, delta_neg or delta_abs")
    }
    val pdata = parentData.get.res.asUpdateData(0)
    val updateTs = pdata.ts.getOrElse(SMGRrd.tssNow)
    val currentTs: Long = pdata.ts.map{ pts =>
      if (isTsMs)
        ((pts * 1000L) + 999) // XXX we have lost precision with truncating the long parent ts, rounding UP here
      else pts
    }.getOrElse( if (isTsMs) System.currentTimeMillis() else SMGRrd.tssNow )
    val deltas = pdata.values.map { tsd => currentTs - tsd.toLong }
    val ret = paramStr match {
      case "age" => deltas.map(d => if (d < 0) 0 else d)
      case "ttl" => deltas.map(d => if (d > 0) 0 else -d)
      case "delta" => deltas
      case "delta_neg" => deltas.map(d => -d)
      case "delta_abs" => deltas.map(d => Math.abs(d))
      case _ => throwOnError(action, paramStr, timeoutSec, s"Invalid ts sub-command - " +
        s"must be one of age, ttl, delta, delta_neg or delta_abs")
    }
    CommandResultListDouble(ret.map(_.toDouble), Some(updateTs))
  }
}
