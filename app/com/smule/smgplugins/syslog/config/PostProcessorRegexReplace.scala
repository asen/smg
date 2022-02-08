package com.smule.smgplugins.syslog.config

import com.smule.smg.core.SMGLoggerApi

import java.util.function.UnaryOperator
import scala.collection.mutable

case class PostProcessorRegexReplace(regex: String, replacement: String) extends StringPostProcessor {

  import java.util.regex.Pattern
  private val pat: Pattern = Pattern.compile(regex)

  private def myReplaceAll(o: Object): String = {
    pat.matcher(o.toString).replaceAll(replacement)
  }

  //XXX workaround for Scala 2.11
  private val myReplaceAllUnaryOperator = new UnaryOperator[Object] {
    override def apply(t: Object): Object = myReplaceAll(t)
  }

  override def process(value:String): String = {
    myReplaceAll(value)
  }

  override def reload(): Unit = {}
}

object PostProcessorRegexReplace {
  def fromYmap(ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[PostProcessorRegexReplace] = {
    if (ymap.contains("regex")){
      Some(PostProcessorRegexReplace(
        regex = ymap("regex").toString,
        replacement = ymap.getOrElse("replace", "").toString
      ))
    } else {
      log.error("PostProcessorRegexReplace.fromYmap: Missing regex value")
      None
    }
  }
}