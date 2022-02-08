package com.smule.smgplugins.syslog.config

import com.smule.smg.config.SMGConfigParser
import com.smule.smg.core.SMGLoggerApi

import scala.collection.mutable

case class PipelineConfig(
                           serverId: String,
                           syslogServerConfig: SyslogServerConfig,
                           lineSchema: LineSchema,
                           smgObjectTemplate: SMGObjectTemplate
                         )

object PipelineConfig {
  def fromYmap(ymap: mutable.Map[String, Object], pluginInterval: Int, log: SMGLoggerApi): Option[PipelineConfig] = {
    try {
      val uid = ymap.get("id").map(_.toString)
      if (uid.isEmpty) {
        log.error("PipelineConfig.fromYmap: invalid or missing id")
        return None
      }
      val serverId = uid.get
      val syslogConf = ymap.get("syslog").flatMap { o =>
        val mm = SMGConfigParser.yobjMap(o)
        SyslogServerConfig.fromYmap(serverId, mm, log)
      }
      if (syslogConf.isEmpty) {
        log.error(s"PipelineConfig.fromYmap($serverId): invalid or missing syslog config map")
        return None
      }
      val lineSchema = ymap.get("schema").flatMap { o =>
        val mm = SMGConfigParser.yobjMap(o)
        LineSchema.fromYmap(serverId, mm, log)
      }
      if (lineSchema.isEmpty) {
        log.error(s"PipelineConfig.fromYmap($serverId): invalid or missing schema config map")
        return None
      }
      val smgObjectTemplate = ymap.get("object_template").flatMap { o =>
        val mm = SMGConfigParser.yobjMap(o)
        SMGObjectTemplate.fromYmap(serverId, pluginInterval ,mm, log)
      }
      if (smgObjectTemplate.isEmpty) {
        log.error(s"PipelineConfig.fromYmap($serverId): invalid or missing object_template config map")
        return None
      }
      Some(
        PipelineConfig(
          serverId = serverId,
          syslogServerConfig = syslogConf.get,
          lineSchema = lineSchema.get,
          smgObjectTemplate = smgObjectTemplate.get
        )
      )
    } catch { case t: Throwable =>
      log.ex(t,s"PipelineConfig.fromYmap: unexpected exception: ${t.getMessage}")
      None
    }
  }
}