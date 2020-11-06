package com.smule.smgplugins.cc.map

import com.smule.smg.core.{CommandResult, CommandResultListString, ParentCommandData, SMGCmdException, SMGLoggerApi}
import com.smule.smgplugins.cc.shared.CCStringUtil

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class SMGMapCommand(log: SMGLoggerApi) {

  private def throwOnError(action: String, paramStr: String,
                           timeoutSec: Int, errMsg: String) = {
    throw SMGCmdException(s":cc $action $paramStr", timeoutSec, -1, "", errMsg)
  }

  case class MapParams(map: Map[String,String], default: Option[String], skipNotMatching: Boolean)

  private def parseParams(inp: String): MapParams = {
    var rem = inp
    var moreKvTokens = true
    val mm = mutable.Map[String, String]()
    while (moreKvTokens){
      val kvt = CCStringUtil.extractKvToken(rem)
      if (kvt.kv.isDefined){
        mm.put(kvt.kv.get._1, kvt.kv.get._2)
        rem = kvt.rem
      } else moreKvTokens = false
    }
    var skipMissing = false
    val dflt = if (rem.isBlank)
      None
    else {
      val qt = CCStringUtil.quotedVal(rem)
      if (qt.isDefined)
        Some(qt.get._1)
      else {
        rem = rem.strip()
        if (rem == "-s" || rem == "--skip"){
          skipMissing = true
          None
        } else
          Some(rem)
      }
    }
    MapParams(mm.toMap, dflt, skipMissing)
  }

  //:cc map k1=v1 k2=v2 [<default>]
  def mapCommand(action: String, paramStr: String, timeoutSec: Int,
                parentData: Option[ParentCommandData]): CommandResult = {
    if (parentData.isEmpty) {
      throwOnError(action, paramStr, timeoutSec, s"Map command expects parent data")
    }
    val params = parseParams(paramStr)
    val parentRes = parentData.get.res
    val inpLines = parentRes match {
      case stringListRes: CommandResultListString => stringListRes.lst
      case _ => parentRes.asStr.split('\n').toList
    }
    val ret = ListBuffer[String]()
    inpLines.foreach { ln =>
      val mapped = params.map.get(ln)
      if (mapped.isDefined)
        ret += mapped.get
      else if (!params.skipNotMatching) {
        if (params.default.isDefined)
          ret += params.default.get
        else
          throwOnError(action, paramStr, timeoutSec,
            s"Map match failure with no default value: ${params.map} ln=$ln")
      }
    }
    CommandResultListString(ret.toList, None)
  }
}
