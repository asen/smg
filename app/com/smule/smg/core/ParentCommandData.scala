package com.smule.smg.core

case class ParentCommandData(stdout: List[String]){
  lazy val asStr: String = stdout.mkString("\n")
}
