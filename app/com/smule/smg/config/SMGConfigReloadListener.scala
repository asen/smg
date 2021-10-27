package com.smule.smg.config

import com.smule.smg.config.SMGConfigReloadListener.ReloadType

trait SMGConfigReloadListener {
  def localOnly: Boolean
  def reload(): Unit
}

object SMGConfigReloadListener {
  object ReloadType extends Enumeration {
    val FULL, LOCAL, REMOTES = Value
  }
}