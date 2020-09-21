package com.smule.smgplugins.jsgraph

import com.smule.smg._
import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.{SMGAggGroupBy, SMGObjectView}
import com.smule.smg.grapher.{GraphOptions, SMGAggObjectView}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginAction, SMGPluginLogger}
import play.api.{Mode, Play}
import play.api.libs.json.Json

/**
  * Created by asen on 10/18/16.
  */

class SMGJsGraphPlugin(val pluginId: String,
                       val interval: Int,
                       val pluginConfFile: String,
                       val smgConfSvc: SMGConfigService
                      ) extends SMGPlugin {

  override val showInMenu: Boolean = false

  override def objects: Seq[SMGObjectView] = Seq()

  override def reloadConf(): Unit = {}

  override def run(): Unit = {}

  override def indexes: Seq[SMGConfIndex] = Seq()

  override val autoRefresh: Boolean = false

  val log = new SMGPluginLogger(pluginId)

  val FETCH_URL_PREFIX = "/fetch/"
  val FETCH_AGG_URL_PREFIX = "/fetchAgg?"

  private def objectFromFetchUrl(furl: String): Option[SMGObjectView] = {
    if (furl.startsWith(FETCH_URL_PREFIX)) {
      // /fetch/someotherhost.dummy3?s=1w
      val restArr = furl.substring(FETCH_URL_PREFIX.length).split("\\?")
      val oid = restArr(0)
      smg.getObjectView(oid)
    } else if (furl.startsWith(FETCH_AGG_URL_PREFIX)) {
      // /fetchAgg?s=1w&op=STACK&ids=someotherhost.dummy3,localhost.dummy3,someotherhost.dummy5
      val restArr = furl.substring(FETCH_AGG_URL_PREFIX.length).split("&").map { s =>
        val a = s.split("=", 2) ;
        (a(0), a(1))
      }
      val pmap = Map(restArr:_*)
      val objs = pmap.getOrElse("ids", "").split(",").map(smg.getObjectView(_)).filter(_.isDefined).map(_.get)
      val groupBy = SMGAggGroupBy.gbParamVal(pmap.get("gb"))
      if (objs.nonEmpty)
        Some(SMGAggObjectView.build(objs, pmap.getOrElse("op", "GROUP"), groupBy, pmap.get("gbp"), None))
      else None
    } else {
      log.error("objectFromFetchUrl: bad fetch Url provided: " + furl)
      None
    }
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    val actionId = httpParams.get("a")
    if (actionId.isEmpty) return {
      <h3>Please use the graphs actions links to access JsGraph functionality ({actions.map(_.name).mkString(", ")})</h3>
    }.mkString

    val objFetchUrl = httpParams.get("o")
    val objView = if (objFetchUrl.isDefined) objectFromFetchUrl(objFetchUrl.get) else None
    if (objView.isEmpty) {
      <h3>Object not found</h3>
      <p>{httpParams}</p>
    }.mkString
    else renderChart(actionId.get, objView.get, objFetchUrl.get)
  }

  import com.smule.smg.remote.SMGRemoteClient._  // needed to serialize the SMGObjectView to json

  private def plotlyRef = if (smgConfSvc.isDevMode){
    <script src="https://cdn.plot.ly/plotly-1.18.0.min.js"></script>
  } else {
    <script src="/assets/plugins/jsgraph/pl/plotly-1.18.0.min.js"></script>
  }
  private def renderChart(actionId:String, ov:SMGObjectView, furl: String): String = {
    <div id="jsgraphDiv">
      { plotlyRef }
      <script>
        var gl_smgAction = { scala.xml.Unparsed("\"" + actionId + "\"") }
        var gl_smgObjectView = { scala.xml.Unparsed(Json.toJson(ov).toString) }
        var gl_smgFetchUrl = { scala.xml.Unparsed("\"" + furl + "\"") }
      </script>
      <div id="plContainer" style="width:100%; height:600px;">Loading ... please wait</div>
      <script src="/assets/plugins/jsgraph/jsgraph.js"></script>
    </div>
  }.mkString

//  override def rawData(httpParams: Map[String,String]): String = ""

  override val actions: Seq[SMGPluginAction] = Seq(
    SMGJsGraphZoomAction(this.pluginId),
    SMGJsGraphHistogramAction(this.pluginId),
    SMGJsGraphDerive1Action(this.pluginId)
  )

}
