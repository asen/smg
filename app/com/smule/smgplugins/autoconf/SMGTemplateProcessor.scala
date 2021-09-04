package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi
import org.fusesource.scalate.TemplateEngine

import java.util

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

  def processTemplate(inputFile: String, context: Map[String,Object]): Option[String] = {
    val scalafiedContext = context.map(t => (t._1, scalafyObject(t._2)))
    try {
      Some(engine.layout(inputFile, scalafiedContext))
    } catch { case t: Throwable =>
      log.error(s"SMGTemplateProcessorprocessTemplate: Error processing template $inputFile message: " +
        s"${t.getMessage} (context: $scalafiedContext)")
      None
    }
  }
}
