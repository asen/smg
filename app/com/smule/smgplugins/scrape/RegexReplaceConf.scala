package com.smule.smgplugins.scrape

import scala.collection.mutable
import scala.util.matching.Regex

case class RegexReplaceConf(regex: String, replace: String, filterRegex: Option[String]) {
  lazy val filterRegexRx: Option[Regex] = filterRegex.map(_.r)
}

object RegexReplaceConf {
  def fromYamlObject(m: mutable.Map[String, Object]): Option[RegexReplaceConf] = {
    if (m.contains("regex") && m.contains("replace"))
      Some(RegexReplaceConf(
        regex = m("regex").toString,
        replace = m("replace").toString,
        filterRegex = m.get("filter_regex").map(_.toString)
      ))
    else None
  }

  def toYamlObject(in: RegexReplaceConf): java.util.Map[String, Object] = {
    val ret = new java.util.HashMap[String, Object]()
    ret.put("regex", in.regex)
    ret.put("replace", in.replace)
    if (in.filterRegex.isDefined)
      ret.put("filter_regex", in.filterRegex)
    ret
  }
}
