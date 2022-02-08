package com.smule.smgplugins.syslog.server

import java.text.SimpleDateFormat
import java.util.Date
import scala.collection.mutable.ListBuffer

case class SyslogServerStatus(
                               serverId: String,
                               listenPort: Int,
                               activeClients: Seq[String],
                               totalClients: Long,
                               totalLines: Long,
                               mergedLines: Long,
                               totalHits: Long,
                               forwardedHits: Long,
                               lastHitTs: Long
                             ) {
  lazy val numActiveClients: Int = activeClients.size
  lazy val lastHitTsDateStr: String = SyslogServerStatus.tsFmt(lastHitTs)

  def inspect(includePort: Boolean = false, skipClients: Boolean = false): String = (if (includePort) s"port=$listenPort " else "") +
    s"serverId=$serverId aClients=$numActiveClients tClients=$totalClients tlLines=$totalLines " +
    s"mLines=$mergedLines tlHits=$totalHits " +
    s"fwHits=$forwardedHits lastHitTs=$lastHitTs lastHitTsDate=$lastHitTsDateStr" +
    (if (skipClients) "" else s" clients=${activeClients.mkString(",")}")


  private val mPrefix = s"gk_syslog_${serverId}_${listenPort}_"

  private def metricStrs(nm: String, v: Number, typ: String, help: String): Seq[String] = {
    val ret = ListBuffer[String]()
    val uid = s"${mPrefix}$nm"
    ret += s"# HELP $uid $help"
    ret += s"# TYPE $uid $typ"
    ret += s"${uid} ${v.toString}"
    ret
  }

  def asMetrics: String = {
    val ret = ListBuffer[String]()
    ret ++= metricStrs("num_accepted_lines", totalLines, "counter",
      "Syslog lines accepted")
    ret ++= metricStrs("num_merged_lines", mergedLines, "counter",
      "Syslog lines after merging line continuations")
    ret ++= metricStrs("num_parsed_lines", totalHits, "counter",
      "Successfully parsed syslog lines")
    ret ++= metricStrs("num_forwarded_lines", forwardedHits, "counter",
      "Syslog lines passed on to processing")
    val age = if (lastHitTs > 0)  System.currentTimeMillis() - lastHitTs else 0
    ret ++= metricStrs("last_write_age", age, "gauge",
      "How many milliseconds ago was the last recieved line")
    ret ++= metricStrs("active_clients", numActiveClients, "gauge",
      "Connected clients")
    ret.mkString("\n") + "\n"
  }
}

object SyslogServerStatus {
  def tsFmt(ts: Long): String = new SimpleDateFormat("YYYY-MM-dd/hh:mm:ss").format(new Date(ts))
}

