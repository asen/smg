package com.smule.smgplugins.calc

case class SMGErrElem(elem: String, err: String) extends ExprElem {
  val kind = "ER"
  override def toS = s"$err:$elem"
}

