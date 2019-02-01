package com.smule.smgplugins.calc

import com.smule.smg.grapher.GraphOptions

case class SMGCalcExpr(seq: Seq[ExprElem]) {

  def toS = seq.map(_.toS).mkString(" ")

  def outputFn(period:String, gopts: GraphOptions): String = {
    exprId + gopts.fnSuffix(period) + ".png"
  }

  def hasErrors:Boolean = seq.exists(_.kind == "ER")

  def exprId: String = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    md.update(toS.getBytes())
    md.digest().map("%02x".format(_)).mkString
  }

  val firstObjectViewElem: Option[SMGObjectViewElem] = seq.find(_.kind == "OV").map(_.asInstanceOf[SMGObjectViewElem])

}

