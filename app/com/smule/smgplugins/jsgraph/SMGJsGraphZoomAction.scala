package com.smule.smgplugins.jsgraph

case class SMGJsGraphZoomAction(pluginId: String) extends SMGJsGraphPluginAction {
  override val actionId: String = "zoom"
  override val name: String = "Zoom"
}

