package com.smule.smg.plugin

import com.smule.smg.core.SMGObjectView

trait SMGPluginAction {
  val actionId: String
  val name: String
  val pluginId: String
  def actionUrl(ov: SMGObjectView, period: String): String
}
