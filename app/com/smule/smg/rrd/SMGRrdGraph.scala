package com.smule.smg.rrd

import com.smule.smg.core.{SMGObjectView, SMGUpdateBatchActor}
import com.smule.smg.grapher.GraphOptions

import scala.collection.mutable

class SMGRrdGraph(val rrdConf: SMGRrdConfig, val objv: SMGObjectView) extends SMGRrdGraphApi {

  import SMGRrd._

  private val rrdFname = {
    if (objv.rrdFile.isEmpty)
      log.error("SMGRrdGraph: received an object without rrdFile: " + objv)
    objv.rrdFile.get
  }

  /**
    * Garph the SMGRrdObject for the specified period
    *
    * @param outfn - output file name
    * @param period - period to cover in the graph
    */
  def graph(outfn:String, period: String, gopts: GraphOptions): Unit = {
    if (rrdConf.useBatchedUpdates && rrdConf.rrdcachedFlushOnRead)
      SMGUpdateBatchActor.flushRrdFile(rrdConf, rrdFname)
    val cmd = rrdGraphCommand(outfn, period, gopts)
    runRrdGraphCommand(rrdConf, cmd)
  }

  private def rrdGraphCommand(outFn: String, period:String, gopts: GraphOptions): String = {
    val cmd = rrdGraphCommandPx(rrdConf, objv.id, outFn, period, gopts.pl, gopts.step,
      gopts.maxY, objv.graphMaxY, gopts.minY, objv.graphMinY, gopts.logY)
    val c = new mutable.StringBuilder(cmd)
    var first = true
    val lblMaker = new LabelMaker()
    val colorMaker = new ColorMaker()
    var lastLabel = ""
    objv.vars.zipWithIndex.foreach { t =>
      val rrdLbl = lblMaker.nextLabel // make sure labelmaker advances even if not graphed
      if (objv.graphVarsIndexes.isEmpty || objv.graphVarsIndexes.contains(t._2)) {
        val v = t._1
        val vlabel = lblFmt(v.label.getOrElse(rrdLbl))
        val cdef = v.cdef
        val srcLabel = if (cdef.isDefined) "cdf_" + rrdLbl else rrdLbl
        c.append(" 'DEF:").append(srcLabel).append("=")
        c.append(rrdFname).append(":").append(rrdLbl).append(":AVERAGE'") // TODO support MAX
        if (!gopts.disablePop) {
          c.append(" 'DEF:pp_").append(srcLabel).append("=")
          c.append(rrdFname).append(":").append(rrdLbl).append(s":AVERAGE:end=now-$period:start=end-$period'") // TODO support MAX
          val ppoffs = parsePeriod(period).getOrElse(0)
          c.append(" 'SHIFT:pp_").append(srcLabel).append(s":$ppoffs'")
        }
        if (cdef.nonEmpty) {
          val cdefSubst = substCdef(cdef.get, srcLabel)
          c.append(" 'CDEF:").append(rrdLbl).append("=").append(cdefSubst).append("'")
          if (!gopts.disablePop) {
            c.append(" 'CDEF:pp_").append(rrdLbl).append("=").append(substCdef(cdef.get, "pp_" + srcLabel)).append("'")
          }
          rrdLbl
        }
        if (objv.cdefVars.isEmpty) {
          val gvStr = graphVar(v, rrdLbl, vlabel, colorMaker, first = first, stacked = objv.stack, gopts)
          first = false
          c.append(gvStr)
        }
        lastLabel = rrdLbl
      }
    }
    if (objv.cdefVars.nonEmpty){
      val cdefLblMaker = new LabelMaker("cd_")
      objv.cdefVars.foreach { cv =>
        val cdefLabel = cdefLblMaker.nextLabel
        val vlabel = lblFmt(cv.label.getOrElse(cdefLabel))
        val cdefSubst = substCdef(cv.cdef.get, lblMaker.prefix)
        c.append(" 'CDEF:").append(cdefLabel).append("=").append(cdefSubst).append("'")
        if (!gopts.disablePop) {
          val ppCdefSubst = substCdef(cv.cdef.get, "pp_" + lblMaker.prefix)
          c.append(" 'CDEF:pp_").append(cdefLabel).append("=").append(ppCdefSubst).append("'")
        }
        val stack = !first && objv.stack
        val gvStr = graphVar(cv, cdefLabel, vlabel, colorMaker, first = first, stacked = objv.stack, gopts)
        first = false
        c.append(gvStr)
        lastLabel = cdefLabel
      }
    }
    c.append(lastUpdated(lastLabel))
    c.append(resolutionRrdStr(objv.interval, period, gopts, objv.rraDef, rrdConf))
    c.toString
  }
}


