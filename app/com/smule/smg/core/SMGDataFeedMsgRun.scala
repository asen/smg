package com.smule.smg.core

import com.smule.smg.rrd.SMGRrd

case class SMGDataFeedMsgRun(interval: Int, errors: List[String], pluginId: Option[String]) {
  val ts: Int = SMGRrd.tssNow
  val isOverlap: Boolean = errors.nonEmpty
}
