package com.smule.smg

import java.io.{File, FileWriter}
import java.nio.file.Paths

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

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

  def flushRrdCachedFile(rrdFile: String): Unit = {
    if (rrdToolSocket.isDefined) {
      log.debug(s"SMGRrdConfig.flushSocket: flushing $rrdFile via " + rrdToolSocket.get)
      try {
        SMGCmd(s"$rrdTool flushcached --daemon ${rrdToolSocket.get} $rrdFile", 120).run
      } catch {
        case t: Throwable => log.ex(t, "Unexpected exception while flushing socket")
      }
    }
  }

  val imageCellWidth: Int = rrdGraphWidth + 83 // rrdtool adds 81 + 2 right padding

  //TODO make this configurable? 2621440 according to getconf ARG_MAX on linux and 262144 on Mac
  val maxArgsLength: Int = 25000
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

  private def getAutoResolution(rrdInterval: Int, period: String, pl: Option[String]): String = {
    val ip = parseRrdPeriod(period) - pl.map(parseRrdPeriod).getOrElse(0)
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

  def getDataResolution(rrdInterval: Int, period: String, gopts: GraphOptions): String = {
    if (gopts.step.isDefined) {
      intervalToStr(gopts.step.get) + " avg (requested)"
    } else getAutoResolution(rrdInterval, period, gopts.pl)
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
    if (rigid) c.append(" --rigid ")
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

  def resolutionRrdStr(interval: Int, period: String, gopts: GraphOptions): String = {
    val resStr = getDataResolution(interval, period, gopts)
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
    val cmd = rrdGraphCommand(outfn, period, gopts)
    runRrdGraphCommand(rrdConf, cmd)
  }

  private def rrdGraphCommand(outFn: String, period:String, gopts: GraphOptions): String = {
    val cmd = rrdGraphCommandPx(rrdConf, objv.id, outFn, period, gopts.pl, gopts.step, gopts.maxY,
      gopts.minY, objv.graphMinY, gopts.logY)
    val c = new mutable.StringBuilder(cmd)
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
    c.append(resolutionRrdStr(objv.interval, period, gopts))
    c.toString
  }
}

/**
  * Class encapsulating graphing aggregate objects (representing multiple SMGObjects and an aggregation function)
  *
  * @param rrdConf - rrdtool configuration
  * @param aggObj - aggregate object to work with
  */
class SMGRrdGraphAgg(val rrdConf: SMGRrdConfig, val aggObj: SMGAggObjectView) {
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
    c.append(resolutionRrdStr(aggObj.interval, period, gopts))
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
    c.append(resolutionRrdStr(aggObj.interval, period, gopts))
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
    val cmd = SMGCmd(fetchCommand(params.resolution, params.period, params.pl))
    val ret = for (ln <- cmd.run
         if ln != ""
         if "ds\\d".r.findFirstMatchIn(ln).isEmpty
         if (!params.filterNan) || "(?i)nan".r.findFirstMatchIn(ln).isEmpty
         if "^\\s+$".r.findFirstMatchIn(ln).isEmpty
    ) yield {
      val arr0 = ln.trim.split(":",2).map(_.trim)
      val tss = arr0(0).toInt
      val arr = arr0(1).split("\\s+").map(_.trim).map(n =>
        if ("(?i)nan".r.findFirstMatchIn(n).nonEmpty) Double.NaN else n.toDouble)
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
    reCalcResolution(ret, params.resolution)
  }

  private def fetchCommand(resolution: Option[Int], period: Option[String], pl: Option[String] = None): String = {
    val c = new mutable.StringBuilder(rrdConf.rrdTool).append(" fetch ")
    if (rrdConf.rrdToolSocket.isDefined) {
      c.append(s" --daemon ${rrdConf.rrdToolSocket.get}").append(" ")
    }
    c.append(rrdFname).append(" ").append(FETCH_CF)
    if (resolution.isDefined) c.append(" --resolution=").append(resolution.get)
    if (period.isDefined) c.append(" --start=-").append(safePeriod(period.get))
    if (pl.isDefined) c.append(" --end=start+").append(safePeriod(pl.get))
    c.toString
  }

}

class SMGRrdFetchAgg(val rrdConf: SMGRrdConfig, val aggobj: SMGAggObjectView) {

  import SMGRrd._

  def fetch(params: SMGRrdFetchParams): List[SMGRrdRow] = {
    val objsData = for ( o <- aggobj.objs ) yield {
      new SMGRrdFetch(rrdConf, o).fetch(params)
    }
    val ret = merge(aggobj.op, objsData)
    reCalcResolution(ret, params.resolution)
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

  def checkOrCreateRrd(ts: Option[Int] = None): Unit = {
    if (!fileExists){
      val cmd = rrdCreateCommand(ts)
      SMGCmd.runCommand(cmd, defaultCommandTimeout)
      log.info("Created new rrd using: " + cmd)
    }
  }

  def updateValues(values: List[Double], ts: Option[Int]): Unit = {
      val tss = if (ts.isEmpty && (obju.dataDelay == 0))
        "N" // rrdtool default
      else if (ts.isEmpty)
        (SMGRrd.tssNow - obju.dataDelay).toString
      else
        (ts.get - obju.dataDelay).toString
      SMGCmd.runCommand(rrdUpdateCommand(tss, values), defaultCommandTimeout)
  }

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
    val myRraDef = if (obju.rraDef.isDefined) obju.rraDef.get else SMGRrd.getDefaultRraDef(obju.interval)
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
}
