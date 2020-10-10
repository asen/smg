package com.smule.smg.monitor

import com.smule.smg.core.SMGLogger
import com.smule.smg.rrd.SMGRrd

case class SMGMonNotifyConf(src: SMGMonAlertConfSource.Value,
                            srcId: String,
                            crit: Seq[String],
                            fail: Seq[String],
                            warn: Seq[String],
                            anom: Seq[String],
                            notifyBackoff: Option[Int],
                            notifyDisable: Boolean,
                            notifyStrikes: Option[Int]
                           ) {
  def inspect: String = {
    s"SMGMonNotifyConf: src=$src, srcId=$srcId, crit=${crit.mkString(",")}, fail=${fail.mkString(",")}, " +
      s"warn=${warn.mkString(",")}, anom=${anom.mkString(",")}, notifyBackoff=$notifyBackoff, " +
      s"notifyDisable=$notifyDisable, notifyStrikes=$notifyStrikes"
  }
}


object SMGMonNotifyConf {

  val log = SMGLogger

  val DEFAULT_NOTIFY_BACKOFF: Int = SMGRrd.parsePeriod("6h").get

  val DEFAULT_NOTIFY_STRIKES: Int = 3

  private val NOTIFY_KEYS = Set(
    "notify-crit",
    "notify-unkn", //replaced by notify-fail
    "notify-fail",
    "notify-warn",
    "notify-anom",
    "notify-backoff",
    "notify-disable",
    "notify-strikes"
  )

  def fromVarMap(src: SMGMonAlertConfSource.Value, srcId: String, vMap: Map[String, String]): Option[SMGMonNotifyConf] = {
    val matchingKeys = vMap.keySet.intersect(NOTIFY_KEYS)
    if (matchingKeys.isEmpty)
      None
    else {
      val notifyCrit = vMap.get("notify-crit").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifyFail = vMap.get("notify-fail").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq()) ++
        vMap.get("notify-unkn").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq()) // deprecated in favor of notify-fail
      val notifyWarn = vMap.get("notify-warn").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifySpike = vMap.get("notify-anom").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifyBackoff = vMap.get("notify-backoff").flatMap { v => SMGRrd.parsePeriod(v) }
      val notifyDisable = vMap.getOrElse("notify-disable", "false") == "true"
      val notifyStrikes = vMap.get("notify-strikes").map(_.toInt)
      Some(SMGMonNotifyConf(src, srcId, notifyCrit, notifyFail, notifyWarn, notifySpike, notifyBackoff,
        notifyDisable, notifyStrikes))
    }
  }

  def isNotifyKey(k: String): Boolean = k.startsWith("notify-")
}

