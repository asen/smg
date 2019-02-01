package com.smule.smg.monitor

case class SMGMonitorLogFilter(periodStr: String, rmtIds: Seq[String], limit: Int, minSeverity: Option[SMGState.Value],
                               inclSoft: Boolean, inclAcked: Boolean, inclSilenced: Boolean,
                               rx: Option[String], rxx: Option[String])

