package com.smule.smgplugins.calc

import java.io.File
import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGObjectVar
import com.smule.smg.grapher.{GraphOptions, SMGImage, SMGImageView}
import com.smule.smg.plugin.SMGPluginLogger
import com.smule.smg.remote.{SMGRemote, SMGRemotesApi}
import com.smule.smg.rrd.SMGRrd
import com.smule.smg.rrd.SMGRrd.ColorMaker

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/**
  * Created by asen on 4/5/16.
  */

class SMGCalcRrd(configSvc: SMGConfigService) {
  val log = new SMGPluginLogger("calc")

  def parseExpr(smg: GrapherApi, remotesApi: SMGRemotesApi, strExpr: String): SMGCalcExpr  = {
    val strExprWithSpaces = "([\\*-\\+/\\(\\)])".r.replaceAllIn(strExpr, {m => " " + m.group(1) + " "})
    val arr = strExprWithSpaces.trim.split("\\s+")
    val seq = arr.map {
      case n if n.matches("\\d+") => SMGNumericElem(n)
      case e@("*" | "-" | "+" | "/" | "(" | ")" | "ADDNAN" ) => SMGOpElem(e)
      case ei => {
        val m = ".*\\[(\\d+)\\]$".r.findFirstMatchIn(ei)
        val vix = if (m.isDefined) m.get.group(1).toInt else 0
        val oid = "\\[.*$".r.replaceAllIn(ei,"")
        val ov = smg.getObjectView(oid)
        if (ov.isEmpty) {
          SMGErrElem(oid, "ERR_NOT_FOUND")
        } else {
          val ovvSize = ov.get.filteredVars(true).size
          if ( vix >= ovvSize) {
            SMGErrElem(ei, s"ERR_INCOMPAT($vix:$ovvSize)")
          } else {
            SMGObjectViewElem(ei, ov.get, vix)
          }
        }
      }
    }

    implicit val futEc = configSvc.executionContexts.rrdGraphCtx

    val futsSeq = seq.toList.map { e =>
      if (e.kind == "OV" && SMGRemote.isRemoteObj(e.asInstanceOf[SMGObjectViewElem].ov.id)) {
        val ovElem = e.asInstanceOf[SMGObjectViewElem]
        remotesApi.downloadRrd(e.asInstanceOf[SMGObjectViewElem].ov).map { ov2 =>
          if (ov2.isDefined)
            SMGObjectViewElem(ovElem.elem, ov2.get, ovElem.vix)
          else
            SMGErrElem(e.elem, "ERR_DOWNLOAD")
        }
      } else Future { e }
    }

    val fut = Future.sequence(futsSeq)
    val futResult = Await.result(fut, Duration(120,"seconds"))
    SMGCalcExpr(futResult)
  }

  def graph(confSvc: SMGConfigService,
            expr: SMGCalcExpr,
            period: String,
            gopts: GraphOptions,
            title: Option[String]): (Option[SMGImageView], Option[String] )= {
    log.debug(expr)
    if (expr.hasErrors)
      return (None, Some("Expression has errors"))
    if (expr.seq.size % 2 == 0)
      return (None, Some("Expression must have odd number of elements"))
    if (expr.firstObjectViewElem.isEmpty)
      return (None, Some("At least one object required in expression"))

    val baseFn = expr.outputFn(period, gopts)
    val outFn = new File(confSvc.config.imgDir, baseFn).toString

    val firstObjElem = expr.firstObjectViewElem.get
    val firstObj = firstObjElem.ov
//    val myVar = firstObjElem.graphVar
    val cmdPx = SMGRrd.rrdGraphCommandPx(confSvc.config.rrdConf,
      title.getOrElse("Calculated graph"),
      outFn, period, None, gopts.step, gopts.maxY, None, gopts.minY, firstObj.graphMinY, gopts.logY)
    val out = new StringBuilder(
      cmdPx
    )
    val colorMaker = new ColorMaker()
    val srcLabelMaker = new SMGRrd.LabelMaker()
    out.append(exprToRrd(expr, period, gopts, colorMaker))

    try {
      SMGRrd.runRrdGraphCommand(confSvc.config.rrdConf, out.toString)
      (Some(SMGImage(firstObj, period, confSvc.config.urlPrefix + "/" + baseFn, gopts)), None)
    } catch {
      case _: Exception => (None, Some("An exception from rrdtool has occured. Command was: " + out.toString()))
    }

  }

  private def exprToRrd(expr: SMGCalcExpr, period:String, gopts: GraphOptions, colorMaker: ColorMaker):String  = {
    val calcLabelMaker = new SMGRrd.LabelMaker("c_")
    val ret = new StringBuilder()
    val defsByOvElem = mutable.HashMap[String, String]()
    // first define all rrd objects
    expr.seq.filter(_.kind == "OV").foreach { oe =>
      val ovElem: SMGObjectViewElem = oe.asInstanceOf[SMGObjectViewElem]
      if (!defsByOvElem.contains(ovElem.elem)) {
        val ov = ovElem.ov
        val rrdLbl = ovElem.graphVarRrdLbl
        val rrdFname = ov.rrdFile.get
        val defLbl = calcLabelMaker.nextLabel
        ret.append(s" 'DEF:$defLbl=$rrdFname:$rrdLbl:AVERAGE'") // TODO support MAX

        if (!gopts.disablePop) {
          ret.append(" 'DEF:pp_").append(defLbl).append("=")
          ret.append(rrdFname).append(":").append(rrdLbl).append(s":AVERAGE:end=now-$period:start=end-$period'") // TODO support MAX
          val ppoffs = SMGRrd.parsePeriod(period).getOrElse(0)
          ret.append(" 'SHIFT:pp_").append(defLbl).append(s":$ppoffs'")
        }

        val cdef = ovElem.graphVar.cdef
        val lbl = if (cdef.nonEmpty) {
          val cdefLbl = "cdf_" + defLbl
          val cdefSubst = SMGRrd.substCdef(cdef.get, defLbl)
          ret.append(" 'CDEF:").append(cdefLbl).append("=").append(cdefSubst).append("'")
          if (!gopts.disablePop) {
            ret.append(" 'CDEF:pp_").append(cdefLbl).append("=").append(SMGRrd.substCdef(cdef.get, "pp_" + defLbl)).append("'")
          }
          cdefLbl
        } else defLbl

        defsByOvElem(ovElem.elem) = lbl
      }
    }
    //calcLabelMaker.reset()
    val exprBuf = ListBuffer(expr.seq:_*)
    ret.append(s" 'CDEF:cc_0=").append(seqToRpn("", exprBuf, "", defsByOvElem.toMap)).append("' ")
    if (!gopts.disablePop) {
      val exprBuf = ListBuffer(expr.seq:_*) // seqToRpn2 destroys the buff
      ret.append(s" 'CDEF:pp_cc_0=").append(seqToRpn("", exprBuf, "pp_", defsByOvElem.toMap)).append("' ")
    }

    val v: SMGObjectVar = expr.firstObjectViewElem.get.graphVar
    ret.append(SMGRrd.graphVar(v, s"cc_0", v.label.getOrElse("calc"), colorMaker, false, false, gopts))

    ret.toString()
  }

  private def plainSeqToRpn(soFar: String, seq: Seq[ExprElem], ovLblPx: String, ovLblMap: Map[String, String] ): String = {
    if (seq.isEmpty) {
      return soFar
    }
    if (soFar == "") {
      plainSeqToRpn(elemToCdef(seq.head, ovLblPx, ovLblMap), seq.tail, ovLblPx, ovLblMap)
    } else {
      val ops = seq.slice(0, 2)
      plainSeqToRpn(soFar + s",${elemToCdef(ops(1), ovLblPx, ovLblMap)},${elemToCdef(ops(0), ovLblPx, ovLblMap)}", seq.slice(2,seq.size), ovLblPx, ovLblMap)
    }
  }

  private def seqToRpn(soFar: String, seq: mutable.ListBuffer[ExprElem], ovLblPx:String, ovLblMap: Map[String, String]): String = {
    // find inner-most parens
    var innerParensRix = seq.indexWhere(_.elem == ")")
    val innerParensLix = if (innerParensRix == -1) {
      innerParensRix = seq.size
      -1
    } else {
      seq.slice(0, innerParensRix).lastIndexWhere(_.elem == "(")
    }
    val forPlainRpn = seq.slice(innerParensLix + 1, innerParensRix)
    val rpnElem = SMGRpnElem(plainSeqToRpn("", forPlainRpn, ovLblPx, ovLblMap))
    seq.remove(Math.max(innerParensLix,0), Math.min(forPlainRpn.size + 2, seq.size))
    if (seq.isEmpty)
      rpnElem.toS
    else {
      seq.insert(innerParensLix, rpnElem)
      seqToRpn(soFar, seq, ovLblPx, ovLblMap)
    }
  }

  private def elemToCdef(e: ExprElem, ovLblPx: String, ovLblMap: Map[String, String]) =
    if (ovLblMap.contains(e.elem)) ovLblPx + ovLblMap(e.elem) else e.elem

}
