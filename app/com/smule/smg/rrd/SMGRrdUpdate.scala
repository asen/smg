package com.smule.smg.rrd

import java.io.File
import java.nio.file.Paths

import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGCmd, SMGObjectUpdate}

import scala.collection.mutable

/**
  * Class encapsulating updating SMGObjects
  *
  * @param obju
  * @param configSvc
  */
class SMGRrdUpdate(val obju: SMGObjectUpdate, val configSvc: SMGConfigService) {
  import SMGRrd._

  val rrdConf: SMGRrdConfig = configSvc.config.rrdConf

  private val rrdFname = obju.rrdFile.get

  def checkOrCreateRrd(ts: Option[Int] = None): Unit = {
    if (!fileExists){
      val cmd = rrdCreateCommand(ts)
      SMGCmd.runCommand(cmd, defaultCommandTimeout)
      log.info("Created new rrd using: " + cmd)
    }
  }

  def updateValues(values: List[Double], ts: Option[Int]): Unit = {
    val tss = expandTs(ts)
    SMGCmd.runCommand(rrdUpdateCommand(tss, values), defaultCommandTimeout)
  }

  def updateValues(updateData: SMGRrdUpdateData): Unit = updateValues(updateData.values, updateData.ts)

  def updateBatch(batch: Seq[SMGRrdUpdateData]): Unit = {
    SMGCmd.runCommand(rrdUpdateBatchCommand(batch), defaultCommandTimeout)
  }

  private def expandTs(ts: Option[Int]): String = if (ts.isEmpty && (obju.dataDelay == 0))
    "N" // rrdtool default
  else if (ts.isEmpty)
    (SMGRrd.tssNow - obju.dataDelay).toString
  else
    (ts.get - obju.dataDelay).toString

  private def fileExists: Boolean = new File(rrdFname).exists()

  private def rrdCreateCommand(ts:Option[Int]): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" create ").append(rrdFname)
    c.append(" --step ").append(obju.interval)
    if (obju.rrdInitSource.isDefined) {
      val fn = obju.rrdInitSource.get
      val absfn = if (fn.contains(File.separator)) {
        fn
      } else { // assume same dir as original if conf value does not contain directory parts
        Paths.get(new File(rrdFname).getParent, fn).toString
      }
      if (new File(absfn).exists()){
        c.append(s" --source $absfn")
      } else {
        //swallow
        log.warn(s"rrdCreateCommand: non-existing init source ($absfn) when creating rrd for ${obju.id} ($rrdFname)")
      }
    } else {
      c.append(" --start ")
      if (ts.isEmpty)
        c.append("-").append(obju.interval * 2 + obju.dataDelay)
      else
        c.append((ts.get - (obju.interval * 2 + obju.dataDelay) ).toString)
    }
    //    c.append(" --no-overwrite")
    val lbl = new LabelMaker()
    obju.vars.foreach { (v: Map[String, String]) =>
      c.append(" DS:").append(lbl.nextLabel).append(":").append(obju.rrdType)
      c.append(":").append((obju.interval * 2.5).toInt).append(":").append(v.getOrElse("min", "0"))
      c.append(":").append(v.getOrElse("max", "U"))
    }
    val myRraDef = if (obju.rraDef.isDefined) obju.rraDef.get else SMGRraDef.getDefaultRraDef(obju.interval)
    c.append(" ").append(myRraDef.defs.mkString(" "))
    c.toString
  }

  private def rrdUpdateCommand(tss: String, vals: List[Double]): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" update ")
    if (rrdConf.rrdToolSocket.nonEmpty) {
      c.append("--daemon ").append(rrdConf.rrdToolSocket.get).append(" ")
    }
    c.append(rrdFname)
    c.append(" ").append(tss).append(":").append(vals.map{ x => numRrdFormat(x, nanAsU = true)}.mkString(":"))
    c.toString
  }

  private def rrdUpdateBatchCommand(batch: Seq[SMGRrdUpdateData]): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" update ")
    if (rrdConf.rrdToolSocket.nonEmpty) {
      c.append("--daemon ").append(rrdConf.rrdToolSocket.get).append(" ")
    }
    c.append(rrdFname)
    batch.foreach { udata =>
      val tss = expandTs(udata.ts)
      c.append(" ").append(tss).append(":").append(udata.values.map{ x => numRrdFormat(x, nanAsU = true)}.mkString(":"))
    }
    c.toString
  }
}


