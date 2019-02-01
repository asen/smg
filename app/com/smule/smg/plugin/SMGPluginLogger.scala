package com.smule.smg.plugin

import com.smule.smg.core.SMGLoggerApi

class SMGPluginLogger(pluginId: String) extends SMGLoggerApi {

  private val logger = play.api.Logger(pluginId)

  override def debug(a:Any): Unit = logger.debug(a.toString)

  override def info(a:Any): Unit = logger.info(a.toString)

  override def warn(a:Any): Unit = logger.warn(a.toString)

  override def error(a:Any): Unit = logger.error(a.toString)

  override def ex(ex:Throwable, msg: String = ""): Unit = {
    if (msg != "")
      error(msg)
    error(ex)
    error(ex.getStackTrace.map(ste => ste.toString).mkString(" "))
  }

}

