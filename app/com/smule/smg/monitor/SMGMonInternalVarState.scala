package com.smule.smg.monitor

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGObjectUpdate

import scala.collection.mutable.ListBuffer

class SMGMonInternalVarState(var objectUpdate: SMGObjectUpdate,
                             vix: Int,
                             val configSvc: SMGConfigService,
                             val monLog: SMGMonitorLogApi,
                             val notifSvc: SMGMonNotifyApi) extends SMGMonInternalState {

  override def alertKey: String = id

  override val id: String = SMGMonInternalVarState.stateId(objectUpdate, vix)
  override val parentId: Option[String] = Some(objectUpdate.id)

  override def pluginId: Option[String] = objectUpdate.pluginId

  override def ouids: Seq[String] = (Seq(objectUpdate.id) ++
    configSvc.config.viewObjectsByUpdateId.getOrElse(objectUpdate.id, Seq()).map(_.id)).distinct
  override def vixOpt: Option[Int] = Some(vix)
  override def oid: Option[String] = Some(objectUpdate.id)
  override def pfId: Option[String] = objectUpdate.preFetch

  private def label =  objectUpdate.vars(vix).getOrElse("label", "ds" + vix)

  override def alertSubject: String = s"${objectUpdate.id}[$vix]:$label"

  override def text: String = s"${objectUpdate.id}[$vix]:$label: ${objectUpdate.title}: $currentStateDesc"

  private def processCounterUpdate(ts: Int, rawVal: Double): Option[(Double,Option[String])] = {
    val tsDelta = ts - myCounterPrevTs

    val ret = if (tsDelta > (objectUpdate.interval * 10)) {
      if (myCounterPrevTs > 0) {
        log.debug(s"SMGMonInternalVarState.processCounterUpdate($id): Time delta is too big: $ts - $myCounterPrevTs = $tsDelta")
      }
      None
    } else if (tsDelta <= 0) {
      // XXX tsDelta == 0 is a vailid case with openmetrics where a counter comes with
      // its timestamp and that may update less frequently than our poll interval
      if (tsDelta < 0)
        log.error(s"SMGMonInternalVarState.processCounterUpdate($id): Negative time delta " +
          s"detected: $ts - $myCounterPrevTs = $tsDelta")
      None
    } else {
      val maxr = objectUpdate.vars(vix).get("max").map(_.toDouble)
      val r = (rawVal - myCounterPrevValue) / tsDelta
      val tpl = if ((r < 0) || (maxr.isDefined && (maxr.get < r))){
        log.debug(s"SMGMonInternalVarState.processCounterUpdate($id): Counter overflow detected: " +
          s"p=$myCounterPrevValue/$myCounterPrevTs c=$rawVal/$ts r=$r maxr=$maxr")
        (Double.NaN, Some(s"ANOM: Counter overflow: p=${objectUpdate.numFmt(myCounterPrevValue, vix)} " +
          s"c=${objectUpdate.numFmt(rawVal, vix)} td=$tsDelta r=${objectUpdate.numFmt(r, vix)} " +
          s"maxr=${objectUpdate.numFmt(maxr.getOrElse(Double.NaN), vix)}"))
      } else
        (r, None)
      Some(tpl)
    }
    myCounterPrevValue = rawVal
    myCounterPrevTs = ts
    ret
  }

  private def myNumFmt(d: Double): String = objectUpdate.numFmt(d, vix)

  def processValue(ts: Int, rawVal: Double): Unit = {
    val valOpt = if (objectUpdate.isCounter) {
      processCounterUpdate(ts, rawVal)
    } else Some((rawVal, None))
    if (valOpt.isEmpty) {
      return
    }
    val (newVal, nanDesc) = valOpt.get
    val alertConfs = configSvc.objectValueAlertConfs(objectUpdate, vix)
    val ret: SMGState = if (newVal.isNaN && configSvc.config.globalNanAsAnomaly) {
      SMGState(ts, SMGState.ANOMALY, s"ANOM: value=NaN (${nanDesc.getOrElse("unknown")})")
    } else if (alertConfs.nonEmpty) {
      val allCheckStates = alertConfs.map { alertConf =>
        alertConf.checkValue(objectUpdate, vix, ts, newVal)
      }
      val errRet = allCheckStates.maxBy(_.state)
      if (alertConfs.nonEmpty && (errRet.state == SMGState.OK)) {
        val descSx = alertConfs.map { _.threshDesc(myNumFmt) }.mkString(", ")
        SMGState(ts,SMGState.OK, s"OK: value=${myNumFmt(newVal)} : $descSx")
      } else
        errRet
    } else {
      SMGState(ts, SMGState.OK, s"OK: value=${myNumFmt(newVal)}")
    }
    if (ret.state != SMGState.OK) {
      log.debug(s"MONITOR: ${objectUpdate.id} : $vix : $ret")
    }
    addState(ret, isInherited = false)
  }

  override protected def notifyCmdsAndBackoff: (Seq[SMGMonNotifyCmd], Int) = {
    lazy val mntype = SMGMonNotifySeverity.fromStateValue(currentStateVal)
    configSvc.objectVarNotifyCmdsAndBackoff(objectUpdate, vix, mntype)
  }

  override protected def getMaxHardErrorCount: Int = {
    configSvc.objectVarNotifyStrikes(objectUpdate, vix)
  }

  override def inspect: String = {
    val ret = ListBuffer[String](super.inspect)
    val alertConfs = configSvc.objectValueAlertConfs(objectUpdate, vix)
    alertConfs.foreach { ac =>
      ac.pluginChecks.foreach { ckc =>
        ret += s"${ckc.ckId}: " + ckc.check.inspectState(objectUpdate, vix, ckc.conf)
      }
    }
    ret.mkString("\n")
  }

}

object SMGMonInternalVarState {
  def stateId(ou: SMGObjectUpdate, vix: Int) = s"${ou.id}:$vix"
}

