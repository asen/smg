package com.smule.smg

/**
  * Created by asen on 11/12/15.
  */

/**
  * A simple logger class wrapping Play's logger
  */
object SMGLogger {

  private val logger = play.api.Logger("smg")

  def debug(a:Any) = logger.debug(a.toString)

  def info(a:Any) = logger.info(a.toString)

  def warn(a:Any) = logger.warn(a.toString)

  def error(a:Any) = logger.error(a.toString)

  def ex(ex:Throwable, msg: String = "") = {
    if (msg != "")
      error(msg)
    error(ex)
    error(ex.getStackTrace.map(ste => ste.toString).mkString(" "))
  }
}
