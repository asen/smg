package com.smule.smgplugins.calc

case class SMGNumericElem(elem: String) extends ExprElem {
  val kind = "NU"
  val num = elem.toDouble
}

