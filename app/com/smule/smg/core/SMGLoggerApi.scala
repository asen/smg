package com.smule.smg.core

trait SMGLoggerApi {
  def debug(a:Any): Unit

  def info(a:Any): Unit

  def warn(a:Any): Unit

  def error(a:Any): Unit

  def ex(ex:Throwable, msg: String = ""): Unit
}
