package com.smule.smg.rrd

import java.io.{File, FileWriter}
import java.nio.file.Paths

import com.smule.smg._
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core._
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

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
  def numRrdFormat(x: Double, nanAsU: Boolean): String = if (x.isNaN && nanAsU)
    "U"
  else if ((x % 1) == 0)
    x.toLong.toString
  else
    "%f".format(x)

  val LABEL_LEN = 16

  def lblFmt(lbl:String): String = {
    // TODO sanitize/ escape single quotes
    val pad = if (lbl.length < LABEL_LEN) " " * (LABEL_LEN - lbl.length) else ""
    lbl + pad
  }

  val DEFAULT_LINE_TYPE = "LINE1"

  def lineType(v: Map[String,String]): String = v.getOrElse("lt", DEFAULT_LINE_TYPE)

  def lineColor(v: Map[String,String], cm: ColorMaker): String = if (v.get("clr").isDefined) v("clr") else cm.nextColor

  //XXX the m suffix is ambiguous and M is not recognized by rrdtool - convert to seconds if m2sec is true
  def safePeriod(period: String, m2sec: Boolean = true): String = {
    if (m2sec && period.matches("^\\w+[Mm]$")) {
      parseRrdPeriod(period).toString
    } else if (period.matches("^\\w+$")) period else {
      log.error("safePeriod: bad period provided: " + period)
      "24h" // TODO use default period
    }
  }

  def parseStep(s:String): Option[Int] = {
    parsePeriod(s)
  }

  def parsePeriod(s:String): Option[Int] = {
    val ret = parseRrdPeriod(s)
    if (ret == 0) None else Some(ret)
  }

  private def parseRrdPeriod(s:String) : Int = {
    if (s.matches("^\\d+[Mmhdyw]?$")) {
      val subs = s.substring(0, s.length - 1)
      s.last match {
        case 'M' => subs.toInt * 60
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
    val minutes = if (rem >= 60) (rem / 60).toString + "M" else ""
    rem = rem % 60
    val seconds = if (rem > 0) rem.toString + "s" else ""
    days + hours + minutes + seconds
  }

  private def getAutoResolution(rrdInterval: Int, period: Int, pl: Option[String],
                                rraDef: SMGRraDef, dataPointsPerImage: Int): String = {
    val myPl = pl.map(parseRrdPeriod).getOrElse(0)
    val timeSpan = if (myPl == 0) period else myPl
    val matchingRra = rraDef.parsedRefCfDefs.find { ri =>  //
      (period <= ri.maxPeriod(rrdInterval)) &&
        (dataPointsPerImage * ri.step(rrdInterval) >= timeSpan)
    }
    val ret = if (matchingRra.isEmpty)
      "Unknown"
    else {
      val ri = matchingRra.get
      intervalToStr(ri.step(rrdInterval))
    }
    ret + " avg (estimated)"
  }

  def getDataResolution(rrdInterval: Int, period: String, gopts: GraphOptions,
                        rraDef: Option[SMGRraDef], dataPointsPerImage: Int): String = {
    val periodStart = parseRrdPeriod(period)
    val myRraDef = rraDef.getOrElse(SMGRraDef.getDefaultRraDef(rrdInterval))
    lazy val autoRes = getAutoResolution(rrdInterval, periodStart, gopts.pl, myRraDef, dataPointsPerImage)
    if (gopts.step.isDefined) {
      val res = intervalToStr(gopts.step.get) + " avg (requested)"
      val minStepAtStart = myRraDef.findMinStepAt(periodStart, rrdInterval).getOrElse(rrdInterval)
      if (gopts.step.get < minStepAtStart) {
        res + ", " + autoRes
      } else res
    } else autoRes
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

  def rrdGraphCommandPx(rrdConf: SMGRrdConfig, title: String, outFn: String,
                        period: String, pl:Option[String], step: Option[Int],
                        maxY: Option[Double], minY: Option[Double],
                        objMinY: Option[Double], logY: Boolean): String = {
    val c = new mutable.StringBuilder("graph ").append(outFn).append(" --imgformat=PNG")
    if (rrdConf.rrdToolSocket.isDefined) {
      c.append(s" --daemon ${rrdConf.rrdToolSocket.get}")
    }
    c.append(" --font '").append(rrdConf.rrdGraphFont.getOrElse("LEGEND:7:monospace")).append("'")
    c.append(" --title '").append(title).append("'")
    c.append(" --start=now-").append(safePeriod(period))
    if (pl.isDefined) {
      c.append(" --end=start+").append(safePeriod(pl.get))
    } else c.append(" --end=now")
    c.append(" --width=").append(rrdConf.rrdGraphWidth)
    c.append(" --height=").append(rrdConf.rrdGraphHeight)
    //c.append(" --full-size-mode")
    var rigid = false
    val myMinY = if (minY.isDefined) minY else objMinY
    if (myMinY.isDefined && (!myMinY.get.isNaN)) {
      c.append(" --lower-limit ").append(numRrdFormat(myMinY.get, nanAsU = false))
      if (logY && myMinY.get > 0)
        c.append(" --logarithmic")
      else
        rigid = true
    }
    if (maxY.isDefined) {
      c.append(" --upper-limit ").append(numRrdFormat(maxY.get, nanAsU = false))
      rigid = true
    }
    if (rigid) c.append(" --rigid")
    if (step.isDefined) c.append(" --step ").append(step.get)
    c.append(" ")
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

  def resolutionRrdStr(interval: Int, period: String, gopts: GraphOptions,
                       rraDef: Option[SMGRraDef], rrdConfig: SMGRrdConfig): String = {
    val resStr = getDataResolution(interval, period, gopts, rraDef, rrdConfig.dataPointsPerImage)
    s" 'COMMENT: step\\: $resStr\\n' "
  }

  private def runRrdGraphCommandPipe(rrdConf: SMGRrdConfig, cmd: String) = {
    val tmpFile = File.createTempFile("smg-rrdt-pipe-", ".cmd")
    //tmpFile.deleteOnExit() we delete explicitly instead
    try {
      try {
        val fw = new FileWriter(tmpFile, false)
        try {
          fw.write(cmd)
        } finally fw.close()
      } catch {
        case t: Throwable => throw SMGCmdException("CREATE_TEMP_RRDTOOL_CMD_FILE",
          defaultCommandTimeout, -1, "Unable to create temp file", t.getMessage)
      }
      SMGCmd.runCommand(rrdConf.rrdTool + " - < " + tmpFile.getAbsolutePath, defaultCommandTimeout)
    } finally {
      tmpFile.delete()
    }
  }

  def runRrdGraphCommand(rrdConf: SMGRrdConfig, cmd: String): Unit = {
    if (cmd.length < rrdConf.maxArgsLength)
      SMGCmd.runCommand(rrdConf.rrdTool + " " + cmd, defaultCommandTimeout)
    else {
      log.warn(s"SMGRrd.runRrdGraphCommand: command string is too long (${cmd.length}) - will use rrdtool - < tmpfile")
      runRrdGraphCommandPipe(rrdConf, cmd)
    }
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

  def validateAggParam(aggParam: Option[String]): Option[String] = {
    aggParam.flatMap { myAgg =>
      myAgg match {
        case "GROUP" | "STACK" | "SUM" | "SUMN" | "SUMNAN" | "AVG" | "MAX" | "MIN" => Some(myAgg)
        case _ => None
      }
    }
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
      case "MAX" => maxNanNumbers( nums )
      case "MIN" => minNanNumbers( nums )
      case s : String => if (s.startsWith("RPN:")){
        rpnNumbers(s.split(":",2)(1), nums)
      } else throw new RuntimeException("Invalid op: " + s)
    }
  }

  private def rpnNumbers(rpn: String, inp: List[List[Double]]) : List[Double] = {
    inp.head.indices.map { ix =>
      val computeVars = inp.map(lst => lst.lift(ix).getOrElse(Double.NaN))
      computeRpnValue(rpn, computeVars)
    }.toList
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
    val ret: Array[Double] = Array.fill(inp.head.size) { 0.0 }
    val counts: Array[Int] = Array.fill(inp.head.size) { 0 }
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

  private def minNanNumbers(inp: List[List[Double]]) : List[Double] = {
    val ret = Array[Double](inp.head:_*)
    inp.tail.foreach { lst =>
      lst.zipWithIndex.foreach { case (d, ix) =>
        if (ret(ix).isNaN)
          ret(ix) = d
        else if (d < ret(ix)) // this will be false if d is NaN
          ret(ix) = d
      }
    }
    ret.toList
  }


  private def maxNanNumbers(inp: List[List[Double]]) : List[Double] = {
    val ret = Array[Double](inp.head:_*)
    inp.tail.foreach { lst =>
      lst.zipWithIndex.foreach { case (d, ix) =>
        if (ret(ix).isNaN)
          ret(ix) = d
        else if (d > ret(ix)) // this will be false if d is NaN
          ret(ix) = d
      }
    }
    ret.toList
  }

  private def groupNumbers(inp: List[List[Double]]) : List[Double] = {
    inp.flatten
  }

  def reCalcResolution(inp: List[SMGRrdRow], targetRes: Option[Int]): List[SMGRrdRow] = {
    if (targetRes.isEmpty)
      return inp
    if (inp.isEmpty)
      return inp
    if (inp.tail.isEmpty)
      return inp
    val inpRes = math.abs(inp.tail.head.tss - inp.head.tss)
    if (inpRes >= targetRes.get)
      return inp
    val inpPointsPerOutputPoint = (targetRes.get.toDouble / inpRes.toDouble).toInt
    def nanRow(ts: Int) = SMGRrdRow(ts, inp.head.vals.map(_ => Double.NaN))
    inp.grouped(inpPointsPerOutputPoint).map { chunk =>
      val minPoints = inpPointsPerOutputPoint / 2
      if (chunk.lengthCompare(minPoints) < 0){
        nanRow(chunk.head.tss)
      } else {
        val sumArr = Array.fill(chunk.head.vals.size)(0.0)
        val cntArr = Array.fill(chunk.head.vals.size)(0)
        chunk.foreach { r =>
          r.vals.zipWithIndex.foreach { t =>
            if (!t._1.isNaN){
              sumArr(t._2) += t._1
              cntArr(t._2) += 1
            }
          }
        }
        SMGRrdRow(chunk.head.tss, chunk.head.vals.indices.map{ix =>
          if (cntArr(ix) < minPoints)
            Double.NaN
          else
            sumArr(ix) / cntArr(ix).toDouble
        }.toList)
      }
    }.toList
  }
}
