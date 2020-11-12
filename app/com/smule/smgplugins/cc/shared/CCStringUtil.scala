package com.smule.smgplugins.cc.shared

import scala.collection.mutable.ListBuffer
import scala.util.Try

object CCStringUtil {

  val QUOTES = Set('\'', '"')

  def quotedVal(inp:String): Option[(String, String)] = {
    var rem = inp
    if (rem.nonEmpty && QUOTES.contains(rem(0))){
      val q = rem(0)
      rem = rem.drop(1)
      val eix = rem.indexOf(q)
      if (eix < 0)
        return None
      val ret = rem.splitAt(eix)
      rem = ret._2.drop(1).stripLeading()
      Some(ret._1, rem)
    } else None
  }

  case class ExtractTokenResult(tkn: String, rem: String)
  private val emptyToken: ExtractTokenResult = ExtractTokenResult("", "")

  def extractToken(inp: String): ExtractTokenResult = {
    if (inp.isBlank)
      return emptyToken
    val rem = inp.stripLeading()
    val qt = quotedVal(rem)
    if (qt.isDefined)
      return ExtractTokenResult(qt.get._1, qt.get._2)
    val arr = rem.split("\\s+", 2)
    ExtractTokenResult(arr(0), arr.lift(1).getOrElse(""))
  }

  def tokenize(inp:String): List[String] = {
    val ret = ListBuffer[String]()
    var rem = inp
    while (!rem.isBlank){
      val tkn = CCStringUtil.extractToken(rem)
      ret += tkn.tkn
      rem = tkn.rem
    }
    ret.toList
  }

  case class ExtractKvTokenResult(kv: Option[(String, String)], rem: String)

  def extractKvToken(inp: String, kvSep: String = "="): ExtractKvTokenResult = {
    lazy val emptyRet = ExtractKvTokenResult(None, inp)
    if (inp.isBlank)
      return emptyRet
    var rem = inp.stripLeading()
    val quotedKeyT = quotedVal(inp)
    val key = if (quotedKeyT.isDefined){
      rem = quotedKeyT.get._2.stripLeading()
      if (!rem.startsWith(kvSep))
        return emptyRet
      rem = rem.drop(kvSep.length).stripLeading()
      quotedKeyT.get._1
    } else {
      val eix = rem.indexOf(kvSep)
      if (eix < 0)
        return emptyRet
      val ret = rem.splitAt(eix)
      rem = ret._2.drop(kvSep.length).stripLeading()
      ret._1.stripTrailing()
    }
    rem = rem.stripLeading()
    val valT = quotedVal(rem)
    val value = if (valT.isDefined){
      rem = valT.get._2
      valT.get._1
    } else {
      // space separated val
      val arr = rem.split("\\s+", 2)
      rem = arr.lift(1).getOrElse("")
      arr(0)
    }
    ExtractKvTokenResult(Some(key, value), rem)
  }

  def normalizeV(v: String): String = {
    val myV = v.toLowerCase
    if (myV.endsWith("bytes")){
      Try(myV.stripSuffix("bytes").stripTrailing().toLong).map(_.toString).getOrElse(v)
    } else if (myV.endsWith("kb")){
      Try(myV.stripSuffix("kb").stripTrailing().toLong * 1024).map(_.toString).getOrElse(v)
    } else if (myV.endsWith("mb")) {
      Try(myV.stripSuffix("mb").stripTrailing().toLong * 1024 * 1024).map(_.toString).getOrElse(v)
    } else if (myV.endsWith("gb")) {
      Try(myV.stripSuffix("gb").stripTrailing().toLong * 1024 * 1024 * 1024).map(_.toString).getOrElse(v)
    } else v
  }
}
