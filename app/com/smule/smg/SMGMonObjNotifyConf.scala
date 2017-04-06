package com.smule.smg

/**
  * Created by asen on 8/30/16.
  */

case class SMGMonNotifyConf(src: SMGMonAlertConfSource.Value,
                            srcId: String,
                            crit: Seq[String],
                            unkn: Seq[String],
                            warn: Seq[String],
                            spike: Seq[String],
                            notifyBackoff: Option[Int],
                            notifyDisable: Boolean,
                            notifyStrikes: Option[Int]
                             )


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

case class SMGMonObjNotifyConf(private val varConfs: Map[Int, Seq[SMGMonNotifyConf]]) {

  def varConf(ix: Int): Seq[SMGMonNotifyConf] = varConfs.getOrElse(ix, Seq())

  def getIsDisabledAndBackoff(ix: Option[Int]):(Boolean, Option[Int]) = {
    val confs = if (ix.isDefined) varConf(ix.get) else varConfs.values.flatten
    val isDisabled = confs.forall(_.notifyDisable) // TODO or use exists?
    val backoffs = confs.map(_.notifyBackoff)
    if (confs.nonEmpty)
      (isDisabled, backoffs.max) // TODO? XXX longer backoff period overrides conflicting shorter backoff period
    else (isDisabled, None)
  }
}
