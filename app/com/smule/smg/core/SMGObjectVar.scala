package com.smule.smg.core

case class SMGObjectVar(m: Map[String,String]) {
  lazy val label: Option[String] = m.get("label")
  lazy val mu: Option[String] = m.get("mu")
  lazy val min: Option[String] = m.get("min")
  lazy val max: Option[String] = m.get("max")
  lazy val maxy: Option[String] = m.get("maxy")
  lazy val cdef: Option[String] = m.get("cdef")
  lazy val clr: Option[String] = m.get("clr") // line color
  lazy val lt: Option[String] = m.get("lt") // line type
}

object SMGObjectVar {
  val empty: SMGObjectVar = SMGObjectVar(Map[String,String]())
}