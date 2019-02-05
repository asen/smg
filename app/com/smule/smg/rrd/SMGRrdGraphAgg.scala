package com.smule.smg.rrd

import com.smule.smg.core.SMGObjectView
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Class encapsulating graphing aggregate objects (representing multiple SMGObjects and an aggregation function)
  *
  * @param rrdConf - rrdtool configuration
  * @param aggObj - aggregate object to work with
  */
class SMGRrdGraphAgg(val rrdConf: SMGRrdConfig, val aggObj: SMGAggObjectView) extends SMGRrdGraphApi{
  import SMGRrd._

  /**
    * Produce a graph from the aggregate object
    *
    * @param outfn - output file name
    * @param period - period to cover in the graph
    */
  def graph(outfn:String, period: String, gopts: GraphOptions): Unit = {
    val cmd = rrdGraphCommand(outfn, period, gopts)
    runRrdGraphCommand(rrdConf, cmd)
  }

  private def defLabelMakersPrefix(ix: Int) = "d" + ix + "_"

  private val defLabelMakers: Seq[LabelMaker] =
    for ((v, i) <- aggObj.vars.zipWithIndex) yield {
      new LabelMaker(defLabelMakersPrefix(i))
    }

  private def resetDefLabelMakers(): Unit = defLabelMakers.foreach(lm => lm.reset())

  private def getAllDefsAndLabelsByVarCdef(period : Option[String]): Seq[Seq[(String,String)]] = {
    val ret = for (v <- aggObj.vars)
      yield { new ListBuffer[(String,String)]() }

    def processDefs(obj:SMGObjectView, objShortId: String): Unit = {
      val rrdFname = obj.rrdFile.get
      val objLabelMaker = new LabelMaker()
      val retIt = ret.iterator
      val defLmIt = defLabelMakers.iterator
      for ( tpl  <- obj.vars.zipWithIndex ) {
        val curObjDsLabel = objLabelMaker.nextLabel // advance the object label makers even if not graphed
        val defLm = defLmIt.next()
        val nextDefLbl = defLm.nextLabel
        if (obj.graphVarsIndexes.isEmpty || obj.graphVarsIndexes.contains(tpl._2)) {
          val v = tpl._1
          val cdef = v.get("cdef")
          val defLbl = if (cdef.nonEmpty) "o_" + nextDefLbl else nextDefLbl
          val vlabel = lblFmt(v.getOrElse("label", defLbl))
          val c = new mutable.StringBuilder("'DEF:").append(defLbl).append("=").append(rrdFname).append(":")
          c.append(curObjDsLabel).append(":AVERAGE'")
          if (period.isDefined) {
            c.append(" 'DEF:pp_").append(defLbl).append("=").append(rrdFname).append(":")
            c.append(curObjDsLabel).append(s":AVERAGE:end=now-${period.get}:start=end-${period.get}'")
            val ppoffs = parsePeriod(period.get).getOrElse(0)
            c.append(" 'SHIFT:pp_").append(defLbl).append(s":$ppoffs'")
          }
          if (cdef.nonEmpty) {
            val cdefSubst = substCdef(cdef.get, defLbl)
            c.append(" 'CDEF:").append(nextDefLbl).append("=").append(cdefSubst).append("'")
            if (period.isDefined) {
              val ppCdefSubst = substCdef(cdef.get, "pp_" + defLbl)
              c.append(" 'CDEF:pp_").append(nextDefLbl).append("=").append(ppCdefSubst).append("'")
            }
          }
          retIt.next() += Tuple2(c.toString, vlabel)
        } else {
          retIt.next()
        }
      }
    }
    val shortIds = SMGAggObjectView.stripCommonStuff('.', aggObj.objs.map(o => o.id)).iterator
    aggObj.objs.foreach( o => processDefs(o, shortIds.next()))
    ret.map(_.toList)
  }

  private def toRpnBinOp(defIds: Seq[String], op: String): String = {
    if (defIds.tail.isEmpty) defIds.head
    else {
      toRpnBinOp(defIds.tail, op) + "," + defIds.head  + s",$op"
    }
  }

  private def toRpnSum(defIds: Seq[String]): String = toRpnBinOp(defIds, "+")
  private def toRpnSumNan(defIds: Seq[String]): String = toRpnBinOp(defIds, "ADDNAN")
  private def toRpnMaxNan(defIds: Seq[String]): String = toRpnBinOp(defIds, "MAXNAN")
  private def toRpnMinNan(defIds: Seq[String]): String = toRpnBinOp(defIds, "MINNAN")


  private def getSumCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnSum(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }

  private def getSumNanCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnSumNan(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }

  private def getAvgCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + defs.map(x => defLabelMaker.nextLabel).mkString(",") +"," + defs.size + ",AVG'", cdefLbl)
  }

  private def getMaxNanCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnMaxNan(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }

  private def getMinNanCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnMinNan(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }


  private def rrdGraphCdefCommand(outFn: String, period:String, gopts: GraphOptions,
                                  getCdefFn: (Seq[String], String, LabelMaker) => (String, String)
                                 ): String = {
    val colorMaker = new ColorMaker()
    val allDefsAndLabelsByVar = getAllDefsAndLabelsByVarCdef(if (!gopts.disablePop) Some(period) else None)
    val c = new mutable.StringBuilder()
    resetDefLabelMakers()
    val cdefLabelMaker = new LabelMaker("cd_")
    val vit = aggObj.vars.iterator
    var lastLabel = ""
    for (tpl <- allDefsAndLabelsByVar.zip(defLabelMakers)) {
      val cdefLabel = cdefLabelMaker.nextLabel // advance even if not graphed
      val allDefsAndLabels = tpl._1
      if (allDefsAndLabels.nonEmpty) {
        val defLabelMaker = tpl._2
        val vlabel = allDefsAndLabels.head._2
        val v = vit.next()
        c.append(" ").append(allDefsAndLabels.map(t => t._1).mkString(" ")).append(" ")
        val ppDefLabelMaker = defLabelMaker.dup("pp_") // preserver the defLabelMaker to be used for prev period
        val (cdef, lbl) = getCdefFn(allDefsAndLabels.map(t => t._1), cdefLabel, defLabelMaker)
        c.append(cdef).append(" ")
        if (!gopts.disablePop) {
          c.append(getCdefFn(allDefsAndLabels.map(t => t._1), "pp_" + cdefLabel, ppDefLabelMaker)._1).append(" ")
        }
        if (aggObj.cdefVars.isEmpty) {
          val gvStr = graphVar(v, lbl, vlabel, colorMaker, first = false, stacked = aggObj.stack, gopts)
          c.append(gvStr)
          lastLabel = lbl
        }
      }
    }
    if (aggObj.cdefVars.nonEmpty) {
      val cdefVarLabelMaker = new LabelMaker("cv_")
      aggObj.cdefVars.foreach {cv =>
        val lbl = cdefVarLabelMaker.nextLabel
        val vlabel = lblFmt(cv.getOrElse("label", lbl))
        val cdefVarSubst = substCdef(cv("cdef"), cdefLabelMaker.prefix)
        c.append(" 'CDEF:").append(lbl).append("=").append(cdefVarSubst).append("'")
        if (!gopts.disablePop) {
          val ppCdefVarSubst = substCdef(cv("cdef"), "pp_" + cdefLabelMaker.prefix)
          c.append(" 'CDEF:pp_").append(lbl).append("=").append(ppCdefVarSubst).append("'")
        }
        val gvStr = graphVar(cv, lbl, vlabel, colorMaker, first = false, stacked = aggObj.stack, gopts)
        c.append(gvStr)
        lastLabel = lbl
      }
    }
    c.append(lastUpdated(lastLabel))
    c.append(resolutionRrdStr(aggObj.interval, period, gopts, aggObj.rraDef, rrdConf))
    c.toString()
  }

  // return (cdefs, vlabel, rrdlbl, var map)
  private def getAllDefsAndLabelsByVarGroup: Seq[(String,String,String,Map[String,String])] = {
    val retbuf = new ListBuffer[(String,String,String,Map[String,String])]()
    val shortIds = SMGAggObjectView.stripCommonStuff('.', aggObj.objs.map(o => o.id)).iterator
    val cdefVarLabelMaker = new LabelMaker("cv_")
    var cdefVarDsIx = 0
    aggObj.objs.foreach { o =>
      val objShortId = shortIds.next()
      val rrdFname = o.rrdFile.get
      val objLabelMaker = new LabelMaker()
      val c = new mutable.StringBuilder()
      val defLmIt = defLabelMakers.iterator
      for ( tpl  <- o.vars.zipWithIndex ) {
        val curObjDsLabel = objLabelMaker.nextLabel // advance the object label makers even if not graphed
        val defLm = defLmIt.next()
        val nextDefLbl = defLm.nextLabel
        if (o.graphVarsIndexes.isEmpty || o.graphVarsIndexes.contains(tpl._2)) {
          val v = tpl._1
          val cdef = v.get("cdef")
          val defLbl = if (cdef.nonEmpty) "o_" + nextDefLbl else nextDefLbl
          val vlabel = lblFmt(objShortId + '-' + v.getOrElse("label", defLbl))
          c.append(" 'DEF:").append(defLbl).append("=").append(rrdFname).append(":")
          c.append(curObjDsLabel).append(":AVERAGE")
          c.append("'")
          if (cdef.nonEmpty) {
            val cdefSubst = substCdef(cdef.get, defLbl)
            c.append(" 'CDEF:").append(nextDefLbl).append("=").append(cdefSubst).append("'")
          }
          if (o.cdefVars.isEmpty) {
            retbuf += Tuple4(c.toString, vlabel, nextDefLbl, v)
            c.clear()
          }
        }
      }
      if (o.cdefVars.nonEmpty) {
        o.cdefVars.foreach {cv =>
          val lbl = cdefVarLabelMaker.nextLabel
          val vlabel = lblFmt(objShortId + '-' + cv.getOrElse("label", lbl))
          val cdefVarSubst = substCdefEx(cv("cdef"), "d", "_ds" + cdefVarDsIx)
          cdefVarDsIx += 1
          c.append(" 'CDEF:").append(lbl).append("=").append(cdefVarSubst).append("'")
          retbuf += Tuple4(c.toString, vlabel, lbl, cv)
        }
      }

    }
    retbuf.toList
  }

  private def rrdGraphGroupCommand(outFn: String, period:String, stacked: Boolean, gopts: GraphOptions): String = {
    val colorMaker = new ColorMaker()
    val c = new mutable.StringBuilder()
    var first = true
    var lastLabel = ""
    val mygopts = GraphOptions.withSome(disablePop = true, disable95pRule = gopts.disable95pRule) // TODO support disablePop = false
    getAllDefsAndLabelsByVarGroup.foreach {t4 =>
      val cdef = t4._1
      val vlabel = t4._2
      val lbl = t4._3
      val v = t4._4
      c.append(" ").append(cdef)
      val gvStr = graphVar(v, lbl, vlabel, colorMaker, first = first, stacked = stacked, mygopts)
      first = false
      c.append(gvStr)
      lastLabel = lbl
    }
    c.append(lastUpdated(lastLabel))
    c.append(resolutionRrdStr(aggObj.interval, period, gopts, aggObj.rraDef, rrdConf))
    c.toString
  }

  private def rrdGraphCommand(outFn: String, period:String, gopts: GraphOptions): String = {
    val cmdPx = rrdGraphCommandPx(rrdConf, aggObj.shortTitle, outFn, period,
      gopts.pl, gopts.step, gopts.maxY, gopts.minY, aggObj.graphMinY, gopts.logY)
    cmdPx +
      (aggObj.op match {
        case "GROUP" => rrdGraphGroupCommand(outFn, period, stacked = false, gopts)
        case "STACK" => rrdGraphGroupCommand(outFn, period, stacked = true, gopts)
        case "SUM" => rrdGraphCdefCommand(outFn, period, gopts, getSumCdef)
        case "SUMN" => rrdGraphCdefCommand(outFn, period, gopts, getSumNanCdef)
        // SUMNAN is deprecated use SUMN instead
        case "SUMNAN" => rrdGraphCdefCommand(outFn, period, gopts, getSumNanCdef)
        case "AVG" => rrdGraphCdefCommand(outFn, period, gopts, getAvgCdef)
        // XXX MAX and MIN are actually MAXNAN and MINNAN currently
        case "MAX" => rrdGraphCdefCommand(outFn, period, gopts, getMaxNanCdef)
        case "MIN" => rrdGraphCdefCommand(outFn, period, gopts, getMinNanCdef)
        case s : String => throw new RuntimeException("Invalid op: " + s)
      })
  }

}


