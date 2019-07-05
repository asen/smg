package com.smule.smg.monitor

import java.util.Date

case class SMGMonAlertActive(alertKey: String, cmdIds: List[String], lastTs: Option[Int]) {

  lazy val lastTss: String = lastTs.map(ts => "(" + new Date(ts.toLong * 1000L).toString + ") ").getOrElse("")

  lazy val text: String = lastTss + cmdIds.sorted.mkString(",")

}
