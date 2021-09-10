package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi
import org.fusesource.scalate.TemplateEngine

import java.util
import scala.util.Try

class SMGTemplateProcessor(log: SMGLoggerApi, preventReload: Boolean = false) {
  private val engine = new TemplateEngine
  // XXX this plugin is loaded using the root java class loader which messes up
  // class resolution. We want to use the "app" class loader used by the SMG core
  // and just get a reference to it via a static class from SMG core
  engine.classLoader = SMGConfigParser.getClass.getClassLoader
  engine.allowReload = !preventReload
  engine.escapeMarkup = false
  log.info(s"SMGTemplateProcessor: using classLoader: ${engine.classLoader}")

  private def scalafyObject(o: Object): Object = {
    o match {
      case _: util.List[Object] @unchecked =>
        SMGConfigParser.yobjList(o).map(o => scalafyObject(o))
      case _: util.Map[String, Object] @unchecked =>
        SMGConfigParser.yobjMap(o).map(t => (t._1, scalafyObject(t._2))).toMap
      case _ =>
        o
    }
  }

  private def getLogContext(scalafiedContext: Map[String,Object]): Map[String,Object] = {
    val dataVal: String = scalafiedContext.get("data").map { d =>
      val ds = d.toString
      "SIZE=" + ds.length.toString + " " + ds.take(200) + (if (ds.length > 200) " (...)" else "")
    }.getOrElse("null")
    scalafiedContext.filter(_._1 != "data").map { t =>
      val v = t._2 match {
        case s: String => s"'${s}'"
        case o => o
      }
      (t._1, t._2)
    } ++ Map( "data" -> dataVal)
  }

  def processTemplate(inputFile: String, context: Map[String,Object]): Option[String] = {
    try {
      val scalafiedContext = context.map(t => (t._1, scalafyObject(t._2)))
      try {
        Some(engine.layout(inputFile, scalafiedContext))
      } catch {
        case t: Throwable =>
          val logContext = Try(getLogContext(scalafiedContext)).getOrElse(Map("getLogContext" -> "UNEXPECTED_ERROR"))
          log.ex(t, s"SMGTemplateProcessor.processTemplate: Error processing template $inputFile message: " +
            s"${t.getMessage} cause: ${Option(t.getCause).map(_.toString).getOrElse("null")} Context: ${logContext}")
          None
      }
    } catch { case t: Throwable =>
      log.ex(t, s"SMGTemplateProcessor.processTemplate: Unexpected error while trying to scalafy context: " +
        s"${t.getMessage} Context: ${context.toString().take(5000)}")
      None
    }
  }
}
