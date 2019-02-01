package com.smule.smgplugins.jsgraph

import com.smule.smg.core.SMGObjectView
import com.smule.smg.grapher.GraphOptions
import com.smule.smg.plugin.SMGPluginAction

abstract class SMGJsGraphPluginAction extends SMGPluginAction {
  override def actionUrl(ov: SMGObjectView, period: String): String = {
    //    val myPeriod = if (actionId == "zoom") // TODO ? XXX a hack - double period for zoom ?
    //      SMGRrd.parsePeriod(period).map(pi => (2 * pi).toString).getOrElse(period)
    //    else period
    "/plugin/" + pluginId + "?a=" + actionId +
      "&o=" + java.net.URLEncoder.encode(ov.fetchUrl(period, GraphOptions.default), "UTF-8") // TODO support step?
  }
}

