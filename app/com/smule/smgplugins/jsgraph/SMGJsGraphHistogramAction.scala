package com.smule.smgplugins.jsgraph

case class SMGJsGraphHistogramAction(pluginId: String) extends SMGJsGraphPluginAction {
  override val actionId: String = "hist"
  override val name: String = "Histogram"
}

