package com.smule.smgplugins.rrdchk

import java.io.File
import java.nio.file.{Files, StandardCopyOption}

import com.smule.smg._

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
  * Created by asen on 3/31/17.
  */
object SMGRrdCheckUtil {

  def rrdInfo(smgConfSvc: SMGConfigService, ou:SMGObjectUpdate): SMGRrdCheckInfo = {
    val cmd = SMGCmd(s"${smgConfSvc.config.rrdConf.rrdTool} info ${ou.rrdFile.get}")
    val raw = try {
      cmd.run
    } catch {
      case c: SMGCmdException => {
        List("ERROR: rrdtool info $rrdFile failed", "--------STDOUT--------", c.stdout, "--------STDERR--------",c.stderr)
      }
      case t: Throwable => {
        List(s"ERROR: rrdtool info ${ou.rrdFile.getOrElse("ERROR_MISSING_FILE")} failed - unexpected error")
      }
    }
    SMGRrdCheckInfo(ou, raw)
  }

  def dsName(vix: Int) = s"ds$vix"

  def rrdTune(smgConfSvc: SMGConfigService, rrdFile: String, tuneWhat: String, vix: Int, newVal: Double): Boolean = {
    val tuneOpt = if (tuneWhat == "min") "-i" else "-a"
    val tuneVal = SMGRrd.numRrdFormat(newVal, nanAsU = true)
    val cmd = SMGCmd(s"${smgConfSvc.config.rrdConf.rrdTool} tune $rrdFile $tuneOpt ${dsName(vix)}:$tuneVal")
    try {
      cmd.run
      true
    } catch {
      case t: Throwable => {
        SMGLogger.ex(t, s"Unexpected error from ${cmd.str}")
        false
      }
    }
  }

  def rebuildRrd(smgConfSvc: SMGConfigService, ou:SMGObjectUpdate): Boolean = {
    if(ou.rrdFile.isEmpty)
      return false
    val mysx = "._rrdchk-plugin_"
    val newRrdFile = ou.rrdFile.get + mysx
    val newFobj = new File(newRrdFile)
    if (newFobj.exists()){
      newFobj.delete()
    }
    val tempOu = SMGRrdObject(
      id = ou.id + mysx,
      command = SMGCmd("echo 0"),
      vars = ou.vars,
      title = ou.title,
      rrdType = ou.rrdType,
      interval = ou.interval,
      dataDelay = ou.dataDelay,
      stack = false,
      preFetch = ou.preFetch,
      rrdFile = Some(newRrdFile),
      rraDef = ou.rraDef,
      rrdInitSource = Some(ou.rrdFile.get),
      notifyConf = None
    )
    val rrdU = new SMGRrdUpdate(tempOu, smgConfSvc)
    rrdU.checkOrCreateRrd(None)
    val oldFobjPath = new File(ou.rrdFile.get).toPath
    val ret = Try(
      Files.move(newFobj.toPath, oldFobjPath, StandardCopyOption.REPLACE_EXISTING) == oldFobjPath
    ).getOrElse(false)
    smgConfSvc.config.rrdConf.flushRrdCachedFile(ou.rrdFile.get)
    ret
  }
}
