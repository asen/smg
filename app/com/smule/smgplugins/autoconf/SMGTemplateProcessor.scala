package com.smule.smgplugins.autoconf

import com.smule.smg.config.SMGConfigParser
import org.fusesource.scalate.{Binding, RenderContext, TemplateEngine}

import java.util

class SMGTemplateProcessor() {

  private val engine = new TemplateEngine
  engine.allowReload = true

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

  def processTemplate(inputFile: String, context: Map[String,Object]): String = {
    val scalafiedContext = context.map(t => (t._1, scalafyObject(t._2)))
    engine.layout(inputFile, scalafiedContext)
  }
}
