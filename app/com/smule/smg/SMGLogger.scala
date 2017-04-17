package com.smule.smg


trait SMGLoggerApi {
  def debug(a:Any): Unit

  def info(a:Any): Unit

  def warn(a:Any): Unit

  def error(a:Any): Unit

  def ex(ex:Throwable, msg: String = ""): Unit
}

/**
  * A simple logger class wrapping Play's logger
  */
object SMGLogger extends SMGLoggerApi {

  private val logger = play.api.Logger("smg")

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
