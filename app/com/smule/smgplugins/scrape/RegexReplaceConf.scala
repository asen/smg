package com.smule.smgplugins.scrape

import scala.util.matching.Regex

case class RegexReplaceConf(regex: String, replace: String, filterRegex: Option[String]) {
  lazy val filterRegexRx: Option[Regex] = filterRegex.map(_.r)
}
