package com.smule.smgplugins.rrdchk

import com.smule.smg._
import play.api.libs.json.Json

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * Created by asen on 3/31/17.
  */
class SMGRrdCheckPlugin (val pluginId: String,
                         val interval: Int,
                         val pluginConfFile: String,
                         val smgConfSvc: SMGConfigService
                        ) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)

  override def objects: Seq[SMGObjectView] = Seq()

  override def indexes: Seq[SMGConfIndex] = Seq()

  override def run(): Unit = {
    // we only run on demand
  }

  override def reloadConf(): Unit = {
    // nothing to reload
  }

  override val autoRefresh: Boolean = false // our UI does not want to auto-refresh

  override def rawData(httpParams: Map[String, String]): String = ""

  override val actions: Seq[SMGPluginAction] = Seq()

  override def htmlContent(httpParams: Map[String, String]): String = {
    val aopt = httpParams.get("a")
    val ou: Option[SMGObjectUpdate] = ouFromParams(httpParams)
    val errors = ListBuffer[String]()
    if (aopt.isDefined) {
      lastBgCheckTime = None // invalidate current results as far as display is concerned
      if (aopt.get == "tune" && ou.isDefined) {
        val vix = httpParams("vix").toInt
        val v = httpParams("v").toDouble
        if (!processTuneRequest(ou.get, vix, httpParams("nm"), v)) {
          errors += s"Tune request failed: ${ou.get.id}[$vix], ${httpParams("nm")}=${v.toString}, check logs for details"
        }
      } else if (aopt.get == "bgcheck" ) {
        if (!launchBgCheck)
          errors += "Bg check/fix is aleady running"
      } else if (aopt.get == "bgfix" ) {
        if (!launchBgFix)
          errors += "Bg check/fix is aleady running"
      }
      else errors += s"Invalid action or object id: ${aopt.get} ${ou.map(_.id).getOrElse("")}"
    }
    renderHtmlContent(ou, errors.toList)
  }

  private def ouFromParams(httpParams: Map[String, String]): Option[SMGObjectUpdate] = {
    val smgconf = smgConfSvc.config
    httpParams.get("oid").flatMap { oid =>
      if (smgconf.updateObjectsById.contains(oid)) {
        Some(smgconf.updateObjectsById(oid))
      } else if (smgconf.viewObjectsById.contains(oid)) {
        smgconf.viewObjectsById(oid).refObj
      } else None
    }
  }

  private def processTuneRequest(ou: SMGObjectUpdate, vix: Int, tuneWhat: String, newVal: Double): Boolean = {
    SMGRrdCheckUtil.rrdTune(smgConfSvc, ou.rrdFile.get, tuneWhat, vix, newVal)
  }

  private def textInput(nm: String, v: String, sz: Int, id: Option[String] = None) = scala.xml.Unparsed(
    s"<input size='$sz' type='text' name='$nm' value='$v' " +
      (if (id.isDefined) s"id='${id.get}' " else "") +
      s"/>"
  )

  private def hiddenInput(nm: String, v: String) = scala.xml.Unparsed(s"<input type='hidden' name='$nm' value='$v' />")


  private def goodBad(rrdVal: Any, ouVar: Any) = {
    if (rrdVal.toString == ouVar.toString) {
      <font color="green">conf={ouVar.toString} == rrd={rrdVal.toString}</font>
    } else {
      <font color="red">conf={ouVar.toString} != rrd={rrdVal.toString}</font>
    }
  }

  private def tuneMinMaxForm(ou: SMGObjectUpdate, vix: Int, name: String, default: String) = {
    <form style="float: left" method="POST">
      {hiddenInput("oid", ou.id)}
      {hiddenInput("vix", vix.toString)}
      {hiddenInput("a", "tune")}
      {hiddenInput("nm", name)}
      {textInput("v", ou.vars(vix).getOrElse(name, default), 12)}
      <input type="Submit" value="Tune RRD"/>
    </form>
      <div style="clear: left"></div>
  }

  private def varPropsLi(ou: SMGObjectUpdate, vix: Int, varInfo: SMGRrdVarInfo) = {
    val varDef = ou.vars(vix)
    <li>
      index:
      {varInfo.index}
      , var def =
      {Json.toJson(varDef)}<div style="clear: left"></div>
      type:
      {goodBad(varInfo.rrdType, ou.rrdType)}<div style="clear: left"></div>
      <span style="float: left">min:
        {goodBad(varInfo.min, varDef.getOrElse("min", "0.0").toDouble)}&nbsp;
      </span>{tuneMinMaxForm(ou, vix, "min", "0.0")}<span style="float: left">max:
      {goodBad(varInfo.max, varDef.getOrElse("max", "NaN").toDouble)}&nbsp;
    </span>{tuneMinMaxForm(ou, vix, "max", "NaN")}
    </li>
  }

  private def linkElem(href: String, txt: String) = scala.xml.Unparsed(s"<a href='$href'>$txt</a>")

  private def rrdInfoDetailLink(info: SMGRrdCheckInfo) = {
    <p>{linkElem("?oid=" + info.ou.id, "Show Details")}</p><hr/>
  }

  private def rrdInfoItemHtml(info: SMGRrdCheckInfo) = {
    <div>
      <h4>
        {info.ou.id}
        (
        {info.ou.title}
        )</h4>
      <p>Step:
        {goodBad(info.step, info.ou.interval)}
      </p>
      <p>Num vars:
        {goodBad(info.vars.size, info.ou.vars.size)}
      </p>{if (info.vars.size != info.ou.vars.size) {
      <p>
        <font color="red">CRITICAL ERROR - rrd file incompatible with definition:
          rrd vars={info.vars.size} conf vars={info.ou.vars.size}
          (updates are likely failing)</font>
      </p>
    } else {
      <ul>
        {info.vars.zipWithIndex.map(t => varPropsLi(info.ou, t._2, t._1))}
      </ul>
    }}
    </div>
  }

  private def rrdInfoItemRawOutput(info: SMGRrdCheckInfo) = {
    <hr/>
    <h4 align="center">Raw rrdtool info output below</h4>
    <pre>
      {scala.xml.Unparsed(info.raw.mkString("\n"))}
    </pre>
  }

  private def rrdInfoHtml(ou: SMGObjectUpdate) = {
    if (ou.rrdFile.isEmpty) {
      <p>ERROR: No rrd file defined</p>
    } else {
      val info = SMGRrdCheckUtil.rrdInfo(smgConfSvc, ou)
      rrdInfoItemHtml(info) ++ rrdInfoItemRawOutput(info)
    }
  }

  private var lastBgCheckResult = List[SMGRrdCheckInfo]()
  private var lastBgCheckTime: Option[Int] = None

  private def bgCheckRunningForm = if (checkRunning) {
    <div>
      <font color="red">
        <strong>Background check/fix is running at the moment </strong>
      </font>
      <a href="?">Refresh</a>
    </div>
  } else {
    <div>
      <form method="POST">
        {hiddenInput("a", "bgcheck")}
        <input type="Submit" value="Start background check"/>
      </form>
      {
        if (lastBgCheckResult.nonEmpty) {
          <form method="POST" onsubmit="return confirm('Are you sure you want to fix ALL reported issues?')">
            {hiddenInput("a", "bgfix")}
            <input type="Submit"
                   value="Fix ALL background check issues"
            />
          </form>
        } else {
          <p></p>
        }
      }
    </div>
  }

  private def bgCheckIssue(r: SMGRrdCheckInfo) = {
    <li>
      {rrdInfoItemHtml(r)}
    </li>
  }

  private def bgCheckListResults = if (lastBgCheckTime.isEmpty) {
    <p></p>
  } else if (lastBgCheckResult.isEmpty) {
    <h4 align="center"><font color="green">No issues reported</font></h4>
  } else {
    <div>
    <ul>
      { lastBgCheckResult.map(info => bgCheckIssue(info) ++ rrdInfoDetailLink(info)) }
    </ul>
    </div>
  }

  private def bgCheckStatus = {
    <div>
      {
        if (lastBgCheckTime.isEmpty) {
          <h4 align="center">No valid background check run info available</h4>
        } else {
          <h4 align="center">Last background check run at
            {SMGState.formatTss(lastBgCheckTime.get)}
          </h4>
        }
      }
      <div>{ bgCheckRunningForm }</div>
    </div>
  }

  private def runBgCheck(): Unit = {
    log.info("runBgCheck - START")
    val issues = ListBuffer[SMGRrdCheckInfo]()
    val myConf = smgConfSvc.config
    val toProcess = myConf.updateObjects.size
    var progr = 0
    myConf.updateObjects.foreach { ou =>
      val info = SMGRrdCheckUtil.rrdInfo(smgConfSvc, ou)
      if (!info.isOk) issues += info
      progr += 1
      if (progr % 1000 == 0)
        log.info(s"runBgCheck progress - $progr/$toProcess")
    }
    lastBgCheckResult = issues.toList
    lastBgCheckTime = Some(SMGRrd.tssNow)
    log.info("runBgCheck - FINISHED")
  }


  private def launchBgCheck: Boolean = {
    if (checkAndSetRunning){
      Future {
        try {
          runBgCheck()
        } catch {
          case t: Throwable => {
            log.ex(t, "Unexpected error from runBgCheck")
          }
        } finally {
          finished()
        }
      } (smgConfSvc.executionContexts.defaultCtx)
      true
    } else false
  }

  private def bgFixIssues(info: SMGRrdCheckInfo): Unit = {
    if (info.vars.size != info.ou.vars.size){
      log.error(s"runBgFix - can not fix discrepancy in number of variables: ${info.ou.id}")
      return
    }
    info.vars.zipWithIndex.foreach { t =>
      val vi = t._1
      val ix = t._2
      val v = info.ou.vars(ix)
      val confMax = v.getOrElse("max", "NaN").toDouble
      if (vi.max.toString != confMax.toString){
        val success = SMGRrdCheckUtil.rrdTune(smgConfSvc, info.ou.rrdFile.get, "max", ix, confMax)
        log.info(s"runBgFix - tuned ${info.ou.id}[$ix] max=$confMax oldMax=${vi.max} success=$success")
      }
      val confMin = v.getOrElse("min", "0.0").toDouble
      if (vi.min.toString != confMin.toString){
        val success = SMGRrdCheckUtil.rrdTune(smgConfSvc, info.ou.rrdFile.get, "min", ix, confMin)
        log.info(s"runBgFix - tuned ${info.ou.id}[$ix] min=$confMin oldMin=${vi.min} success=$success")
      }
    }
  }

  private def runBgFix(): Unit = {
    if (lastBgCheckResult.isEmpty) {
      log.info("runBgFix - no results to fix")
      return
    }
    log.info("runBgFix - START")
    lastBgCheckResult.foreach { info =>
      val freshInfo = SMGRrdCheckUtil.rrdInfo(smgConfSvc, info.ou)
      if (!info.isOk) bgFixIssues(freshInfo)
    }
    lastBgCheckResult = List()
    lastBgCheckTime = None
    log.info("runBgFix - FINISHED")
  }

  private def launchBgFix: Boolean = {
    if (checkAndSetRunning){
      Future {
        try {
          runBgFix()
          runBgCheck() // run a new check right after the fix
        } catch {
          case t: Throwable => {
            log.ex(t, "Unexpected error from runBgFix/runBgCheck")
          }
        } finally {
          finished()
        }
      } (smgConfSvc.executionContexts.defaultCtx)
      true
    } else false
  }


  private def renderHtmlContent(ou: Option[SMGObjectUpdate], errors: Seq[String]) = {
    <div>
      {errors.map { s =>
      <h5 align="center">
        <font color="red">ERROR:
          {s}
        </font>
      </h5>
    }}<form>
      {textInput("oid", ou.map(_.id).getOrElse(""), 100, Some("rrdchk-form-oid"))}<input type="submit" value="Show"/>
    </form>
      <script language="JavaScript">
        smgAutocompleteSetup('rrdchk-form-oid', '/json/rxtokens');
      </script>
    </div>
        <hr/>
      <div>
        {bgCheckStatus}
        {if (ou.isEmpty) {
        <div>
          <div>{ bgCheckListResults }</div>
        </div>
      } else {
        <div>{
            if (lastBgCheckTime.nonEmpty) {
              <p><a href="?">Show Background check results</a></p>
            } else {
              <p></p>
            }
          }
          {rrdInfoHtml(ou.get)}
        </div>
      }}
      </div>
  }.mkString

}
