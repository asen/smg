package com.smule.smgplugins.calc

trait ExprElem {
  val elem: String
  val kind: String

  def toS = elem
}

