package com.smule.smg.monitor

import com.smule.smg.core.SMGLogger
import com.smule.smg.rrd.SMGRrd

case class SMGMonNotifyConf(src: SMGMonAlertConfSource.Value,
                            srcId: String,
                            crit: Seq[String],
                            unkn: Seq[String],
                            warn: Seq[String],
                            spike: Seq[String],
                            notifyBackoff: Option[Int],
                            notifyDisable: Boolean,
                            notifyStrikes: Option[Int]
                           ) {
  def inspect: String = {
    s"SMGMonNotifyConf: src=$src, srcId=$srcId, crit=${crit.mkString(",")}, unkn=${unkn.mkString(",")}, " +
      s"warn=${warn.mkString(",")}, spike=${spike.mkString(",")}, notifyBackoff=$notifyBackoff, " +
      s"notifyDisable=$notifyDisable, notifyStrikes=$notifyStrikes"
  }
}


object SMGMonNotifyConf {

  val log = SMGLogger

  val DEFAULT_NOTIFY_BACKOFF: Int = SMGRrd.parsePeriod("6h").get

  val DEFAULT_NOTIFY_STRIKES: Int = 3

  private val NOTIFY_KEYS = Set(
    "notify-crit",
    "notify-unkn",
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
      val notifyUnk = vMap.get("notify-unkn").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifyWarn = vMap.get("notify-warn").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifySpike = vMap.get("notify-anom").map { v => v.split("\\s*,\\s*").toSeq }.getOrElse(Seq())
      val notifyBackoff = vMap.get("notify-backoff").flatMap { v => SMGRrd.parsePeriod(v) }
      val notifyDisable = vMap.getOrElse("notify-disable", "false") == "true"
      val notifyStrikes = vMap.get("notify-strikes").map(_.toInt)
      Some(SMGMonNotifyConf(src, srcId, notifyCrit, notifyUnk, notifyWarn, notifySpike, notifyBackoff,
        notifyDisable, notifyStrikes))
    }
  }

  def isNotifyKey(k: String): Boolean = k.startsWith("notify-")
}

