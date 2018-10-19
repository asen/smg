package com.smule.smgplugins.mon.ex

import com.smule.smg._

import scala.collection.concurrent.TrieMap

class ExtendedCheck(val ckId: String, log: SMGLoggerApi, configSvc: SMGConfigService) extends SMGMonCheck {

  private val configCache = TrieMap[String, ExtendedCheckConf]()

  private def confFromStr(confStr: String): ExtendedCheckConf =
    configCache.getOrElseUpdate(confStr, { ExtendedCheckConf(confStr) })

  /**
    * The actual check interface
    *
    * @param ou        - the rrd/update object being checked
    * @param vix       - the variable index
    * @param ts        - the timestamp at which the new value was fetched
    * @param newVal    - the new value for the variable
    * @param checkConf - the check config passed to alert-p-...:
    * @return
    */
  override def checkValue(ou: SMGObjectUpdate, vix: Int, ts: Int, newVal: Double, checkConf: String): SMGState = {
    def numFmt = { (d: Double) => ou.numFmt(d, vix, applyCdef = false)}
    val ckConf = confFromStr(checkConf)
    val ov = ou.asInstanceOf[SMGObjectView] // XXX TODO?
    val f = new SMGRrdFetch(configSvc.config.rrdConf, ov)
    val params = SMGRrdFetchParams(ckConf.fetchStep, ckConf.fetchStep.map(i => (i * 4).toString),
      None, filterNan = false)
    val rawFdata = f.fetch(params)
    // chop the last NaN if any
    val fdata = if (rawFdata.lastOption.exists(r => r.vals(vix).isNaN))
      rawFdata.dropRight(1)
    else
      rawFdata
    val curVal = fdata.lastOption.map(r => r.vals(vix)).getOrElse(Double.NaN)
    if (!ckConf.isActiveAt(Some(ts)))
      return SMGState(ts, SMGState.OK, s"inactive: c=${numFmt(curVal)} ($checkConf)")
    if (ckConf.checkCrit(curVal))
      return SMGState(ts, SMGState.CRITICAL, s"c=${numFmt(curVal)} " +
        s"${ckConf.critCheck.get._1} ${ckConf.critCheck.get._2} ($checkConf)")
    if (ckConf.checkWarn(curVal))
      return SMGState(ts, SMGState.WARNING, s"c=${numFmt(curVal)} " +
        s"${ckConf.warnCheck.get._1} ${ckConf.warnCheck.get._2} ($checkConf)")
    if (curVal.isNaN){
      return SMGState(ts, SMGState.ANOMALY, s"c=${numFmt(curVal)} ($checkConf)")
    }
    SMGState(ts, SMGState.OK, s"c=${numFmt(curVal)} ($checkConf)")
  }

  def cleanupObsoleteStates(pluginId: String): Unit = {
    configCache.clear()
  }

  override def inspectState(ou: SMGObjectUpdate, vix: Int, checkConf: String): String = {
    checkValue(ou, vix, SMGRrd.tssNow, 0.0, checkConf).toString
  }
}
