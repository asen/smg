package com.smule.smgplugins.jmx

import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.SMGPluginLogger
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._

/**
  * Config parser for the JMX plugin.
  *
  */
class SMGJmxConfigParser(val pluginId: String,
                         val pluginConfFile: String,
                         val configSvc: SMGConfigService,
                         val log: SMGPluginLogger) {

  case class SMGJmxConfig(
                         timeoutMs: Long
                         )

  private var myConf = SMGJmxConfig(
    timeoutMs = SMGJmxConnection.DEFAULT_RMI_SOCKET_TIMEOUT_MS
  )

  def reload(): Unit = {
    try {
      val confTxt = configSvc.sourceFromFile(pluginConfFile)
      val yaml = new Yaml();
      val yMap = yaml.load(confTxt).asInstanceOf[java.util.Map[String, Object]].asScala
      val tmt = yMap.getOrElse("rmi_socket_timeout",
        SMGJmxConnection.DEFAULT_RMI_SOCKET_TIMEOUT_MS.toString).toString.toLong
      myConf = SMGJmxConfig(
        timeoutMs = tmt
      )
    } catch { case t: Throwable =>
      log.ex(t, s"SMGJmxConfigParser.reload: Unexpected exception: ${t.getMessage}")
    }
  }

  def conf: SMGJmxConfig = myConf
}
