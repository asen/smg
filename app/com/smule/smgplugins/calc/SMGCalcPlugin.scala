package com.smule.smgplugins.calc

import com.smule.smg._
import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.SMGObjectView
import com.smule.smg.grapher.{GraphOptions, SMGImageView}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smg.rrd.SMGRrd
import org.yaml.snakeyaml.Yaml

import scala.io.Source
import scala.xml.Unparsed
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer


case class SMGCalcExprIndex(id: String,
                            expr: String,
                            title: Option[String],
                            period: Option[String],
                            step: Option[Int],
                            maxy: Option[Double],
                            miny: Option[Double],
                            dpp: Boolean,
                            d95p: Boolean
                      )
/**
  * Created by asen on 4/5/16.
  */
class SMGCalcPlugin (val pluginId: String,
                     val interval: Int,
                     val pluginConfFile: String,
                     val smgConfSvc: SMGConfigService
                    ) extends SMGPlugin {

  override def objects: Seq[SMGObjectView] = Seq()

  private var topLevelConfMap = Map[String,Object]()

  private var expressions = List[SMGCalcExprIndex]()

  private def keyValFromMap(m: java.util.Map[String, Object]): (String, Object) = {
    val firstKey = m.keys.collectFirst[String] { case x => x }.getOrElse("")
    val retVal = m.remove(firstKey)
    if (retVal != null)
      (firstKey, retVal)
    else
      (firstKey, m)
  }

  override def reloadConf(): Unit = {
    try {
      val confTxt = Source.fromFile(pluginConfFile).mkString
      val yaml = new Yaml();
      val newMap = yaml.load(confTxt).asInstanceOf[java.util.Map[String, Object]]
      val tmp = newMap(pluginId).asInstanceOf[java.util.Map[String, Object]]
      if (tmp != null) {
        topLevelConfMap.synchronized {
          topLevelConfMap = tmp.toMap
          if (topLevelConfMap.contains("expressions")) {
            val expLb = ListBuffer[SMGCalcExprIndex]()
            topLevelConfMap("expressions").asInstanceOf[java.util.ArrayList[Object]].foreach { obj =>
              val ymap = obj.asInstanceOf[java.util.Map[String, Object]]
              val (k, m) = keyValFromMap(ymap)
              val omap = m.asInstanceOf[java.util.Map[String, Object]].toMap
              expLb += SMGCalcExprIndex(k,
                omap.getOrDefault("expr", "").asInstanceOf[String],
                omap.get("title").map(_.toString),
                omap.get("period").map(_.toString),
                omap.get("step").map(_.asInstanceOf[Int]),
                omap.get("maxy").map(_.asInstanceOf[Double]),
                omap.get("miny").map(_.asInstanceOf[Double]),
                omap.getOrElse("dpp", "off") == "on",
                omap.getOrElse("d95p", "off") == "on"
              )
            }
            expressions = expLb.toList
          }
        }
      }
      log.debug("SMGCalcPlugin.reloadConf: confMap=" + topLevelConfMap)
      log.info("SMGCalcPlugin.reloadConf: confMap.size=" + topLevelConfMap.size)
    } catch {
      case t: Throwable => log.ex(t, "SMGCalcPlugin.reloadConf: Unexpected exception: " + t)
    }
  }

  override def run(): Unit = {}

  override def indexes: Seq[SMGConfIndex] = Seq()

  override val autoRefresh: Boolean = true

  val log = new SMGPluginLogger(pluginId)

  val smgCalcRrd = new SMGCalcRrd(smgConfSvc)

  override def htmlContent(httpParams: Map[String,String]): String = {
    val ixOpt = if (httpParams.contains("ix")) expressions.find(_.id == httpParams("ix")) else None

    val strExpOpt = if (ixOpt.isDefined) Some(ixOpt.get.expr) else httpParams.get("expr")
    val strPeriod = if (ixOpt.isDefined) ixOpt.get.period.getOrElse(GrapherApi.detailPeriods.head) else httpParams.getOrElse("period", GrapherApi.detailPeriods.head)
    val titleOpt = if (ixOpt.isDefined) ixOpt.get.title else httpParams.get("title")
    val stepOpt = if (ixOpt.isDefined) ixOpt.get.step
        else if (httpParams.contains("step") && (httpParams("step") != "")) SMGRrd.parseStep(httpParams("step"))
        else None
    val myDisablePop = if (ixOpt.isDefined) ixOpt.get.dpp else httpParams.getOrElse("dpp", "off") == "on"
    val myDisable95p = if (ixOpt.isDefined) ixOpt.get.d95p else httpParams.getOrElse("d95p", "off") == "on"
    val maxYOpt = if (ixOpt.isDefined) ixOpt.get.maxy
        else if (httpParams.getOrElse("maxy", "") == "") None
        else Some(httpParams("maxy").toDouble)
    val gopts = GraphOptions.withSome(step = stepOpt, xsort = None,
      disablePop = myDisablePop, disable95pRule = myDisable95p, maxY = maxYOpt, minY = None) // TODO support minY
    val expOpt = if (strExpOpt.isDefined) {
      Some(smgCalcRrd.parseExpr(smg, remotes, strExpOpt.get))
    } else None
    val (gOpt, errOpt) = if (expOpt.isDefined) {
      smgCalcRrd.graph(smgConfSvc, expOpt.get, strPeriod, gopts, titleOpt)
    } else
      (None, Some("No expression provided"))
    renderHtmlContent(expOpt, errOpt, gOpt, strPeriod, gopts, httpParams)
  }


  private def exprHtmlLi(expr: SMGCalcExprIndex): String = {
    <li>
      <a href={ s"/plugin/calc?ix=${expr.id}" }>{ expr.title.getOrElse("Calculated graph") }</a>
    </li>
  }.mkString

  private def listExpressionsHtmlContent: String = {
    val myexpr = expressions
    if (myexpr.isEmpty)
      { <b>None</b> }.mkString
    else {
      <ul>
        {
        Unparsed(
          myexpr.map { e =>
            exprHtmlLi(e)
          }.mkString
        )
        }
      </ul>
    }.mkString
  }

  private def imgHtmlContent(g: SMGImageView): String = {
    s"<img src='${g.imageUrl}'></img>"
  }

  private def textInput(nm: String, v : String, sz: Int) = s"<input id='id_$nm' size='$sz' type='text' name='$nm' value='$v' />"

  private def cbInput(nm: String, checked: Boolean) = s"<input id='id_$nm' type='checkbox' name='$nm' ${if (checked) "checked" else ""} />"

  private def renderHtmlContent(expOpt: Option[SMGCalcExpr], errOpt: Option[String], gOpt: Option[SMGImageView],
                                period: String, gopts: GraphOptions, httpParams: Map[String,String]): String = {
    <h3>Custom Calculated Graph (Beta)</h3>
      <form method="GET">
        <p>
          <label for="calc_expr_textartea">Enter expression below.<br/>
            An expression consists of object ids (optionally) followed by [idx] (where idx is the 0-based var index,
            0 if not specified) or number literals with an arithmetic operation (one of +, -, * and /) between those.<br/>
            Use "ADDNAN" for NaN safe addition (treat NaN as 0.0). Parentheses ("(" and ")") MUST be used to ensure operators precedence
            (otherwise expression is processed left to right).</label><br/>
          <textarea id="calc_expr_textartea" name="expr" cols="160" rows="6">{expOpt.map(_.toS).getOrElse("")}</textarea>
        </p>
        <hr/>
        <div class="row">
          <div class="col-md-2" style="width: 13em;">
            <label class="manualsubmit-lbl" for="id_period">Period:</label>
              {scala.xml.Unparsed(textInput("period", period, 9))}
          </div>
          <div class="col-md-2" style="width: 13em;">
            <label class="manualsubmit-lbl" for="id_step">Step:</label>
              {scala.xml.Unparsed(textInput("step", gopts.step.map(_.toString).getOrElse(""), 9))}
          </div>
          <div class="col-md-2" style="width: 15em;">
            <label class="manualsubmit-lbl" for="id_maxy">MaxY:</label>
              {scala.xml.Unparsed(textInput("maxy", gopts.maxY.map(_.toString).getOrElse(""), 14))}
          </div>
          <div class="col-md-2" style="width: 17em;">
            <label class="manualsubmit-lbl" for="id_dpp">Disable Period-Over-Period:</label>
              {scala.xml.Unparsed(cbInput("dpp", gopts.disablePop))}
          </div>
          <div class="col-md-2" style="width: 15em;">
            <label class="manualsubmit-lbl" for="id_d95p">Disable 95%-ile line:</label>
              {scala.xml.Unparsed(cbInput("d95p", gopts.disable95pRule))}
          </div>
        </div>
        <hr/>
        <div class="row">
          <div class="col-md-2">
            <input type="submit" value="Get Result" />
          </div>
        </div>
      </form>
      <hr/>
    <div>{errOpt.getOrElse("")}</div>
      <div>{Unparsed(gOpt.map(imgHtmlContent(_)).getOrElse(""))}</div>
    <hr/>
    <h4>Pre-configured expressions:</h4>
    <div>{Unparsed(listExpressionsHtmlContent)}</div>
  }.mkString
  // <div>{expOpt.map(_.toString).getOrElse("")}</div>

}
