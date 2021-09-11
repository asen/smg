package com.smule.smgplugins.autoconf

case class SMGAutoTargetStatus(aConf: SMGAutoTargetConf, errorStatus: Option[String]) {
  val isOk: Boolean = errorStatus.isEmpty
}
