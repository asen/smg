package com.smule.smg

import java.io.File
import java.nio.file.Paths

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Created by asen on 10/22/15.
 */

/**
  * rrdtool configuration
  *
  * @param rrdTool - path to rrdtool executable
  * @param rrdToolSocket - optional path to rrdtool socket file to be used for updates
  */
case class SMGRrdConfig(rrdTool: String ,
                        rrdToolSocket: Option[String],
                        rrdGraphWidth: Int,
                        rrdGraphHeight: Int,
                        rrdGraphFont: Option[String]) {
  private val log = SMGLogger

  def flushSocket(): Unit = {
    if (rrdToolSocket.isDefined) {
      log.debug("SMGRrdConfig.flushSocket: flushing updates via " + rrdToolSocket.get)
      try {
        SMGCmd("echo FLUSHALL | socat - UNIX-CONNECT:" + rrdToolSocket.get, 120).run
      } catch {
        case t: Throwable => log.ex(t, "Unexpected exception while flushing socket")
      }
    }
  }

  val imageCellWidth = rrdGraphWidth + 83 // rrdtool adds 81 + 2 right padding
}

/**
  * RRDTool wrappers helper functions and definitions
  */
object SMGRrd {
  val log = SMGLogger

  val defaultCommandTimeout = 30

//  val LINE_COLORS = Array("#D20000", "#161218", "#FF8200", "#417EB4", "#A1A600", "#5B6104",
//    "#D6C8C7", "#B6959D", "#F0D842", "#555E52", "#C0C7C3", "#809FAC",
//    "#DADA55", "#7D7D7D", "#E6C4BE", "#DED6C7", "#650003", "#574864")

  val LINE_COLORS = Array("#1aadce", "#161218", "#910000", "#8bbc21", "#2f7ed8", "#492970",
                          "#ffff99", "#77a1e5", "#c42525", "#a6c96a", "#ffff00")

  val GPRINT_NUM_FORMAT = "%8.2lf%s"

  def numFmt(v: Map[String,String]): String = if (v.contains("mu"))
      GPRINT_NUM_FORMAT + v("mu").replaceAll("%","%%")
    else GPRINT_NUM_FORMAT

  //TODO - insert a + after the E instead? rrdtool doesn't like 2.5E9 (treats it as 2.5)
  def numRrdFormat(x: Double): String = if ((x % 1) == 0) x.toLong.toString else "%f".format(x)

  val LABEL_LEN = 16

  def lblFmt(lbl:String): String = {
    // TODO sanitize/ escape single quotes
    val pad = if (lbl.length < LABEL_LEN) " " * (LABEL_LEN - lbl.length) else ""
    lbl + pad
  }

  val DEFAULT_LINE_TYPE = "LINE1"

  def lineType(v: Map[String,String]): String = v.getOrElse("lt", DEFAULT_LINE_TYPE)

  def lineColor(v: Map[String,String], cm: ColorMaker): String = if (v.get("clr").isDefined) v("clr") else cm.nextColor

  def safePeriod(period: String): String = if (period.matches("^\\w+$") ) period else {
    log.error("safePeriod: bad period provided: " + period)
    "UNKNOWN"
  }

  def parseStep(s:String): Option[Int] = {
    if (s.matches("^\\d+[mhd]?$")) {
      val subs = s.substring(0, s.length - 1)
      s.last match {
        case 'm' => Some(subs.toInt * 60)
        case 'h' => Some(subs.toInt * 3600)
        case 'd' => Some(subs.toInt * 86400)
        case _ => Some(s.toInt)
      }
    } else None
  }

  def parsePeriod(s:String): Option[Int] = {
    val ret = parseRrdPeriod(s)
    if (ret == 0) None else Some(ret)
  }

  private def parseRrdPeriod(s:String) : Int = {
    if (s.matches("^\\d+[mhdyw]?$")) {
      val subs = s.substring(0, s.length - 1)
      s.last match {
        case 'm' => if (subs.toInt > 12) subs.toInt * 60 else subs.toInt * 86400 * 30
        case 'h' => subs.toInt * 3600
        case 'd' => subs.toInt * 86400
        case 'w' => subs.toInt * 86400 * 7
        case 'y' => subs.toInt * 86400 * 365
        case _ => s.toInt
      }
    } else 0
  }

  private def intervalToStr(int: Int): String = {
    var rem = int
    val days = if (rem >= 86400) (rem / 86400).toString + "d" else ""
    rem = rem % 86400
    val hours = if (rem >= 3600) (rem / 3600).toString + "h" else ""
    rem = rem % 3600
    val minutes = if (rem >= 60) (rem / 60).toString + "m" else ""
    rem = rem % 60
    val seconds = if (rem > 0) rem.toString + "s" else ""
    days + hours + minutes + seconds
  }

  private def getAutoResolution(rrdInterval: Int, period: String): String = {
    val ip = parseRrdPeriod(period)
    val s = if (ip <= 0){
      "Unk"
    } else {
      val retSecs = if (ip <= parseRrdPeriod("30h")) {
        rrdInterval
      } else if (ip < parseRrdPeriod("4d")) {
        Math.max(parseRrdPeriod("300"), rrdInterval)
      } else if (ip <= parseRrdPeriod("13d")) {
        Math.max(parseRrdPeriod("30m"), rrdInterval)
      } else if (ip <= parseRrdPeriod("31d")) {
        Math.max(parseRrdPeriod("2h"), rrdInterval)
      } else {
        Math.max(parseRrdPeriod("1d"), rrdInterval)
      }
      intervalToStr(retSecs)
    }
    s + " avg (guess)"
  }

  def getDataResolution(rrdInterval: Int, period: String, step: Option[Int]): String = {
    if (step.isDefined) {
      intervalToStr(step.get) + " avg (requested)"
    } else getAutoResolution(rrdInterval, period)
  }

  def tssNow: Int  = (System.currentTimeMillis() / 1000).toInt

  /**
    * Helper to pick next color from the pallete
    */
  class ColorMaker {
    private var idx = 0
    private val colors = LINE_COLORS

    def nextColor: String = {
      if (idx >= colors.length) {
        log.warn("ColorMaker: exhausted all colors")
        idx = 0
      }
      val ret = colors(idx)
      idx += 1
      ret
    }
  }

  /**
    * generate sequence of rrd data source (DS) labels compatible with data source names in rrd files produced by mrtg
    *
    * @param px - prefix the generated ids with that prefix
    */
  class LabelMaker(px:String = "") {
    private var idx = -1
    private val labelsPx = "ds"

    def prefix: String = px + labelsPx

    def nextLabel: String = {
      idx += 1
      prefix + idx.toString
    }

    def reset(): Unit = idx = -1

    protected def setIdx(newIdx: Int): Unit = idx = newIdx

    def dup(pp: String = ""): LabelMaker = {
      val ret = new LabelMaker(pp + px)
      ret.setIdx(idx)
      ret
    }
  }

  private def rrdMinutesSteps(v:Int, interval: Int):Int = (v * 60) / interval
  private def rrdDaysRows(v:Int, steps: Int, interval: Int):Int = {
    val mySteps = if (steps == 0) 1 else steps
    ((3600 * 24) / (interval * mySteps)) * v
  }

  def getDefaultRraDef(interval: Int): SMGRraDef = {

    val lst = ListBuffer[String]()

    Seq("AVERAGE", "MAX").foreach{ cf =>
      lst += s"RRA:$cf:0.5:1:" + rrdDaysRows(4,1,interval)
      if (rrdMinutesSteps(5,interval) > 1) // only applcable on 1 min interval
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(5,interval) + ":" + rrdDaysRows(4, rrdMinutesSteps(5,interval),interval)    //5m:4d")
      if (rrdMinutesSteps(30,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(30,interval) + ":" + rrdDaysRows(28, rrdMinutesSteps(30,interval),interval)    //30m:4w")
      if (rrdMinutesSteps(120,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(120,interval) + ":" + rrdDaysRows(120, rrdMinutesSteps(120,interval),interval) //2h:4M")
      if (rrdMinutesSteps(360,interval) > 0)
        lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(360,interval) + ":" + rrdDaysRows(1460, rrdMinutesSteps(360,interval),interval) //6h:4y")
      lst += s"RRA:$cf:0.5:" + rrdMinutesSteps(1440,interval) + ":" + rrdDaysRows(1460, rrdMinutesSteps(1440,interval),interval) //24h:4y")
    }
    SMGRraDef("_default-" + interval, lst.toList)
  }

  def rrdGraphCommandPx(rrdConf: SMGRrdConfig, title: String, outFn: String, period: String, pl:Option[String], step: Option[Int], maxY: Option[Double]): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" graph ").append(outFn).append(" --imgformat=PNG")
    c.append(" --font '").append(rrdConf.rrdGraphFont.getOrElse("LEGEND:7:monospace")).append("'")
    c.append(" --title '").append(title).append("'")
    c.append(" --start=now-").append(safePeriod(period))
    if (pl.isDefined) {
      c.append(" --end=start+").append(safePeriod(pl.get))
    } else c.append(" --end=now")
    c.append(" --width=").append(rrdConf.rrdGraphWidth)
    c.append(" --height=").append(rrdConf.rrdGraphHeight)
    //c.append(" --full-size-mode")
    c.append(" --lower-limit 0 ")
    if (maxY.isDefined) c.append("--upper-limit ").append(maxY.get).append(" --rigid ")
    if (step.isDefined) c.append("--step ").append(step.get).append(" ")
    c.toString
  }

  def substCdef(cdef: String, dsLbl: String): String = cdef.replaceAll("\\$ds", dsLbl)

  def substCdefEx(cdef: String, dsLbl: String, dsSx: String): String = cdef.replaceAll("(\\$ds\\d+)","$1" + dsSx).replaceAll("\\$ds", dsLbl)

  val HTAB = "    " // 4 spaces

  def graphVar(v: Map[String,String], lbl: String, vlabel: String, colorMaker: ColorMaker,
               first: Boolean, stacked: Boolean, gopts: GraphOptions) : String = {
    val stackStr = if ((!first) && stacked) ":STACK" else ""
    val c = new mutable.StringBuilder()
    val clr = lineColor(v,colorMaker)
    c.append(" '").append(lineType(v)).append(":").append(lbl).append(clr).append(":").append(vlabel).append(stackStr).append("'")
    if ((!gopts.disablePop) && (!stacked)) {
      c.append(" '").append(lineType(v)).append(":pp_").append(lbl).append(clr).append("::dashes=2,4").append("'")
    }
    c.append(" 'VDEF:").append(lbl).append("lst=").append(lbl).append(",LAST'")
    c.append(" 'VDEF:").append(lbl).append("std=").append(lbl).append(",STDEV'")
    c.append(" 'VDEF:").append(lbl).append("pct=").append(lbl).append(",95,PERCENTNAN'")
    if ((!gopts.disable95pRule) && (!stacked)) c.append(" 'HRULE:").append(lbl).append("pct").append(clr).append("::dashes=5,10'")
    c.append(" 'GPRINT:").append(lbl).append(s":AVERAGE: avg$HTAB").append(numFmt(v)).append("'")
    c.append(" 'GPRINT:").append(lbl).append(s":MAX: max$HTAB").append(numFmt(v)).append("'")
    c.append(" 'GPRINT:").append(lbl).append(s"lst: last$HTAB").append(numFmt(v)).append("\\n'")
    c.append(" 'GPRINT:").append(lbl).append(s"std:\\t\\t   std$HTAB").append(numFmt(v)).append("'")
    c.append(" 'GPRINT:").append(lbl).append(s"pct: 95%%$HTAB").append(numFmt(v)).append("\\n'")
    c.toString
  }

  def lastUpdated(lastLabel: String): String = if (lastLabel != "")
    " 'COMMENT:\\s' 'GPRINT:" + lastLabel + "lst:last data point from %Y-%m-%d %H\\:%M:strftime' "
  else ""

  def resolutionRrdStr(interval: Int, period: String, step: Option[Int]): String = {
    val resStr = getDataResolution(interval, period, step)
    s" 'COMMENT: step\\: $resStr\\n' "
  }



  private def evalRpn(rpn: mutable.Stack[String]): Double = {
    // TODO VERY limited RPN support
    if (rpn.isEmpty) return Double.NaN
    val cur = rpn.pop()
    cur match {
      case "+" => evalRpn(rpn) + evalRpn(rpn)
      case "-" => {
        val d1 = evalRpn(rpn)
        val d0 = evalRpn(rpn)
        d0 - d1
      }
      case "/" => {
        val d1 = evalRpn(rpn)
        val d0 = evalRpn(rpn)
        if (d1 == 0.0) Double.NaN else d0 / d1
      }
      case "*" => evalRpn(rpn) * evalRpn(rpn)
      case "%" => {
        val d1 = evalRpn(rpn)
        val d0 = evalRpn(rpn)
        if (d1 == 0.0) Double.NaN else d0 % d1
      }
      case "ADDNAN" => {
        val d1 = evalRpn(rpn)
        val d0 = evalRpn(rpn)
        if (d0.isNaN && d1.isNaN) Double.NaN
        else if (d0.isNaN) d1
        else if (d1.isNaN) d0
        else d0 + d1
      }
      case _ => cur.toDouble
    }
  }

  def computeRpnValue(rpnStr: String, vals: List[Double]): Double = {
    //"$ds0,$ds2,*"
    // replace $dsX with actual values (staring from highest indices down)
    var replacedRpnStr = rpnStr
    vals.indices.reverse.foreach { ix =>
      replacedRpnStr = replacedRpnStr.replaceAll("\\$ds" + ix, vals(ix).toString)
    }
    val st = mutable.Stack[String]()
    replacedRpnStr.split(",").foreach(st.push)
    evalRpn(st)
  }

  def computeCdef(cdefStr: String, value: Double): Double = {
    //cdef: "$ds,8,*"
    val replacedRpnStr = cdefStr.replaceAll("\\$ds", value.toString)
    val st = mutable.Stack[String]()
    replacedRpnStr.split(",").foreach(st.push)
    evalRpn(st)
  }

  def mergeValues(op: String, nums: List[List[Double]]): List[Double] = {
    op match {
      case "GROUP" => groupNumbers(nums)
      case "STACK" => groupNumbers(nums)
      case "SUM" => sumNumbers(nums, nanAs0 = false)
      case "SUMN" => sumNumbers(nums, nanAs0 = true)
      // SUMNAN is deprecated use SUMN instead
      case "SUMNAN" => sumNumbers(nums, nanAs0 = true)
      case "AVG" => averageNumbers( nums )
      case s : String => throw new RuntimeException("Invalid op: " + s)
    }
  }

  private def sumNumbers(inp: List[List[Double]], nanAs0: Boolean) : List[Double] = {
    val ret = Array[Double](inp.head.map(n => if (nanAs0 && n.isNaN) 0.0 else n):_*)
    inp.tail.foreach { lst =>
      lst.zipWithIndex.foreach { t =>
        ret(t._2) += (if (nanAs0 && t._1.isNaN ) 0.0 else t._1)
      }
    }
    ret.toList
  }

  private def averageNumbers(inp: List[List[Double]]) : List[Double] = {
    val ret = new Array[Double](inp.head.size)
    val counts = new Array[Int](inp.head.size)
    inp.foreach { lst =>
      lst.zipWithIndex.foreach { t =>
        if ( !t._1.isNaN ) {
          ret(t._2) += t._1
          counts(t._2) += 1
        }
      }
    }
    ret.toList.zipWithIndex.map { t =>
      if (counts(t._2) == 0) Double.NaN else t._1 / counts(t._2)
    }
  }

  private def groupNumbers(inp: List[List[Double]]) : List[Double] = {
    inp.flatten
  }

}



class SMGRrdGraph(val rrdConf: SMGRrdConfig, val objv: SMGObjectView) {

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
    SMGCmd.runCommand(rrdGraphCommand(outfn, period, gopts), defaultCommandTimeout)
  }

  private def rrdGraphCommand(outFn: String, period:String, gopts: GraphOptions): String = {
    val px = rrdGraphCommandPx(rrdConf, objv.id, outFn, period, gopts.pl, gopts.step, gopts.maxY)
    val c = new mutable.StringBuilder(px)
    var first = true
    val lblMaker = new LabelMaker()
    val colorMaker = new ColorMaker()
    var lastLabel = ""
    objv.vars.zipWithIndex.foreach { t =>
      val rrdLbl = lblMaker.nextLabel // make sure labelmaker advances even if not graphed
      if (objv.graphVarsIndexes.isEmpty || objv.graphVarsIndexes.contains(t._2)) {
        val v = t._1
        val vlabel = lblFmt(v.getOrElse("label", rrdLbl))
        val cdef = v.get("cdef")
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
        val vlabel = lblFmt(cv.getOrElse("label", cdefLabel))
        val cdefSubst = substCdef(cv("cdef"), lblMaker.prefix)
        c.append(" 'CDEF:").append(cdefLabel).append("=").append(cdefSubst).append("'")
        if (!gopts.disablePop) {
          val ppCdefSubst = substCdef(cv("cdef"), "pp_" + lblMaker.prefix)
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
    c.append(resolutionRrdStr(objv.interval, period, gopts.step))
    c.toString
  }
}

/**
  * Class encapsulating graphing aggregate objects (representing multiple SMGObjects and an aggregation function)
  *
  * @param rrdConf - rrdtool configuration
  * @param aggObj - aggregate object to work with
  */
class SMGRrdGraphAgg(val rrdConf: SMGRrdConfig, val aggObj: SMGAggObject) {
  import SMGRrd._

  /**
    * Produce a graph from the aggregate object
    *
    * @param outfn - output file name
    * @param period - period to cover in the graph
    */
  def graph(outfn:String, period: String, gopts: GraphOptions): Unit = {
    val cmd = rrdGraphCommand(outfn, period, gopts)
    //    log.info(cmd)
    SMGCmd.runCommand(cmd, defaultCommandTimeout)
  }

  private def defLabelMakersPrefix(ix: Int) = "d" + ix + "_"

  private val defLabelMakers: Seq[LabelMaker] =
    for ((v, i) <- aggObj.vars.zipWithIndex) yield {
      new LabelMaker(defLabelMakersPrefix(i))
    }

  private def resetDefLabelMakers() = defLabelMakers.foreach(lm => lm.reset())

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
    val shortIds = SMGAggObject.stripCommonStuff('.', aggObj.objs.map(o => o.id)).iterator
    aggObj.objs.foreach( o => processDefs(o, shortIds.next()))
    ret.map(_.toList)
  }

  private def toRpnSum(defIds: Seq[String]): String = {
    if (defIds.tail.isEmpty) defIds.head
    else {
      toRpnSum(defIds.tail) + "," + defIds.head  + ",+"
    }
  }

  private def toRpnSumNan(defIds: Seq[String]): String = {
    if (defIds.tail.isEmpty) defIds.head
    else {
      toRpnSumNan(defIds.tail) + "," + defIds.head  + ",ADDNAN"
    }
  }

  private def getSumCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnSum(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }

  private def getSumNanCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + toRpnSumNan(defs.map(x => defLabelMaker.nextLabel)) + "'", cdefLbl)
  }

  private def getAvgCdef(defs: Seq[String], cdefLbl:String, defLabelMaker: LabelMaker): (String, String) = {
    ("'CDEF:" + cdefLbl + "=" + defs.map(x => defLabelMaker.nextLabel).mkString(",") +"," + defs.size + ",AVG'", cdefLbl)
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
    c.append(resolutionRrdStr(aggObj.interval, period, gopts.step))
    c.toString()
  }

  // return (cdefs, vlabel, rrdlbl, var map)
  private def getAllDefsAndLabelsByVarGroup: Seq[(String,String,String,Map[String,String])] = {
    val retbuf = new ListBuffer[(String,String,String,Map[String,String])]()
    val shortIds = SMGAggObject.stripCommonStuff('.', aggObj.objs.map(o => o.id)).iterator
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
    val mygopts = GraphOptions(disablePop = true, disable95pRule = gopts.disable95pRule) // TODO support disablePop = false
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
    c.append(resolutionRrdStr(aggObj.interval, period, gopts.step))
    c.toString
  }

  private def rrdGraphCommand(outFn: String, period:String, gopts: GraphOptions): String = {
    rrdGraphCommandPx(rrdConf, aggObj.shortTitle, outFn, period, gopts.pl, gopts.step, gopts.maxY) + (aggObj.op match {
      case "GROUP" => rrdGraphGroupCommand(outFn, period, stacked = false, gopts)
      case "STACK" => rrdGraphGroupCommand(outFn, period, stacked = true, gopts)
      case "SUM" => rrdGraphCdefCommand(outFn, period, gopts, getSumCdef)
      case "SUMN" => rrdGraphCdefCommand(outFn, period, gopts, getSumNanCdef)
      // SUMNAN is deprecated use SUMN instead
      case "SUMNAN" => rrdGraphCdefCommand(outFn, period, gopts, getSumNanCdef)
      case "AVG" => rrdGraphCdefCommand(outFn, period, gopts, getAvgCdef)
      case s : String => throw new RuntimeException("Invalid op: " + s)
    })
  }

}

/**
  * Class encapsulating fetching data from SMGRrdObjects
  *
  * @param rrdConf - rrdtool config
  * @param objv - object config
  */
class SMGRrdFetch(val rrdConf: SMGRrdConfig, val objv: SMGObjectView) {

  import SMGRrd._

  private val FETCH_CF = "AVERAGE"
  private val rrdFname = objv.rrdFile.get

  def fetch(params: SMGRrdFetchParams): List[SMGRrdRow] = {
    val cmd = SMGCmd(fetchCommand(params.resolution, params.start, params.end))
    for (ln <- cmd.run
         if ln != ""
         if "ds\\d".r.findFirstMatchIn(ln).isEmpty
         if (!params.filterNan) || "(?i)nan".r.findFirstMatchIn(ln).isEmpty
         if "^\\s+$".r.findFirstMatchIn(ln).isEmpty
    ) yield {
      val arr0 = ln.trim.split(":",2).map(_.trim)
      val tss = arr0(0).toInt
      val arr = arr0(1).split("\\s+").map(_.trim).map(n => if ("(?i)nan".r.findFirstMatchIn(n).nonEmpty) Double.NaN else n.toDouble)
      //process cdefs
      objv.vars.zipWithIndex.foreach { case (v, i) =>
        if (v.contains("cdef")) {
          arr(i) = computeCdef(v("cdef"), arr(i))
        }
      }
      //process cdefVars
      if (objv.cdefVars.nonEmpty) {
        val cdLst = objv.cdefVars.map {cv => computeRpnValue(cv("cdef"), arr.toList)}
        SMGRrdRow(tss, cdLst)
      } else {
        val filteredArr = if (objv.graphVarsIndexes.nonEmpty) {
          objv.graphVarsIndexes.map(ix => arr(ix)).toArray
        } else arr
        SMGRrdRow(tss, filteredArr.toList)
      }
    }
  }

  private def fetchCommand(resolution: Option[Int], start: Option[String], end: Option[String] = None): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" fetch ").append(rrdFname).append(" ").append(FETCH_CF)
    if (resolution.isDefined) c.append(" --resolution=").append(resolution.get)
    if (start.isDefined) c.append(" --start=-").append(safePeriod(start.get))
    if (end.isDefined) c.append(" --end=-").append(safePeriod(end.get))
    c.toString
  }

}

class SMGRrdFetchAgg(val rrdConf: SMGRrdConfig, val aggobj: SMGAggObjectView) {

  import SMGRrd._

  def fetch(params: SMGRrdFetchParams): List[SMGRrdRow] = {
    val objsData = for ( o <- aggobj.objs ) yield {
      new SMGRrdFetch(rrdConf, o).fetch(params)
    }
    merge(aggobj.op, objsData)
  }

  private def merge(op: String, inp: Seq[List[SMGRrdRow]]) : List[SMGRrdRow] = {
    val byTs = inp.flatten.groupBy(_.tss)
    byTs.keys.toList.sorted.map { ts =>
      val toMerge = byTs(ts)
      val nums = toMerge.toList.map(_.vals)
      val rowVals = mergeValues(op, nums)
      SMGRrdRow(ts, rowVals)
    }
  }


}

/**
  * Class encapsulating updating SMGObjects
  * @param obju
  * @param configSvc
  */
class SMGRrdUpdate(val obju: SMGObjectUpdate, val configSvc: SMGConfigService) {
  import SMGRrd._

  val rrdConf: SMGRrdConfig = configSvc.config.rrdConf

  private val rrdFname = obju.rrdFile.get

  /**
    * Create a new rrd file matching the configured object or update an existing one
    */
  def createOrUpdate(ts: Option[Int] = None): List[Double] = {
    if (!fileExists) create(ts)
    update(ts)
  }

  private def fileExists: Boolean = new File(rrdFname).exists()

  private def create(ts:Option[Int]): Unit = {
    val cmd = rrdCreateCommand(ts)
    SMGCmd.runCommand(cmd, defaultCommandTimeout)
    log.info("Created new rrd using: " + cmd)
  }

  /**
    * Update an existing rrd with current values
    *
    * @param ts - optional timestamp of the update
    */
  def update(ts:Option[Int]): List[Double] = {
    // fetchValues can block for up to conf.timeoutSec + defaultCommandTimeout seconds,
    // which is better than launching tons of fetch processes?
    log.debug("Fetching values for [" + obju.id + "]")
    try {
      var values : List[Double] = List()
      try {
        values = obju.fetchValues
      } catch {
        case cex: SMGCmdException => {
          log.error("Failed fetch command [" + obju.id + "]: " + cex.getMessage)
          configSvc.sendObjMsg(
            SMGDFObjMsg(ts.getOrElse(tssNow), obju, List(), cex.exitCode,
              List(cex.cmdStr + s" (${cex.timeoutSec})", cex.stdout, cex.stderr))
          )
          return List()
        }
      }
      val tss = if (ts.isEmpty) "N" else ts.get.toString
      SMGCmd.runCommand(rrdUpdateCommand(tss, values), defaultCommandTimeout)
      log.debug("Updated values for [" + obju.id + "]:" + values.toString)
      configSvc.sendObjMsg(
          SMGDFObjMsg(ts.getOrElse(tssNow), obju, values, 0, List())
        )
      values
    } catch {
      case e:Throwable => {
        log.ex(e, "Exception in update: [" + obju.id + "]: " + e.toString)
        configSvc.sendObjMsg(
          SMGDFObjMsg(ts.getOrElse(tssNow), obju, List(), -1, List("update_error"))
        )
        List()
      }
    }
  }

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
        c.append("-").append(obju.interval * 2)
      else
        c.append((ts.get - (obju.interval * 2)).toString)
    }
//    c.append(" --no-overwrite")
    val lbl = new LabelMaker()
    obju.vars.foreach { (v: Map[String, String]) =>
      c.append(" DS:").append(lbl.nextLabel).append(":").append(obju.rrdType)
      c.append(":").append((obju.interval * 2).toString).append(":").append(v.getOrElse("min", "0"))
      c.append(":").append(v.getOrElse("max", "U"))
    }
    val myRraDef = if (obju.rraDef.isDefined) obju.rraDef.get else SMGRrd.getDefaultRraDef(obju.interval)
    c.append(" ").append(myRraDef.defs.mkString(" "))
    c.toString
  }

  private def rrdUpdateCommand(tss: String, vals: List[Double]): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" update ")
    if (rrdConf.rrdToolSocket.nonEmpty) c.append("--daemon unix:").append(rrdConf.rrdToolSocket.get).append(" ")
    c.append(rrdFname)
    c.append(" ").append(tss).append(":").append(vals.map{ x => numRrdFormat(x)}.mkString(":"))
    c.toString
  }
}
