package com.smule.smgplugins.syslog.shared

import com.smule.smgplugins.syslog.config.LineSchema

trait LineParser {

  val schema: LineSchema

  def parseData(ln: String): Option[LineData]

}
