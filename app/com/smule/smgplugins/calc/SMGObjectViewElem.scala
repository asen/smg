package com.smule.smgplugins.calc

import com.smule.smg.core.SMGObjectView

case class SMGObjectViewElem(elem: String, ov: SMGObjectView, vix:Int) extends ExprElem {
  val kind = "OV"

  def graphVar = ov.filteredVars(false)(vix)

  def graphVarRrdLbl = s"ds${vix}"  // TODO find the real index cause vix points to filteredVars
}

