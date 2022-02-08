package com.smule.smgplugins.syslog.config

import com.smule.smg.core.SMGLoggerApi

import scala.collection.mutable
import scala.util.Try

case class SyslogServerConfig(
                               serverId: String,
                               bindHost: String,
                               bindPort: Int,
                               numParsers: Int,
                               statsUpdateNumLines: Long
                             )

object SyslogServerConfig {
  def fromYmap(serverId: String, ymap: mutable.Map[String, Object], log: SMGLoggerApi): Option[SyslogServerConfig] = {
    val port = Try(ymap("port").asInstanceOf[Int]).toOption
    if (port.isEmpty){
      log.error(s"SyslogServerConfig.fromYmap($serverId): invalid or missing port number")
      None
    } else {
      Some(
        SyslogServerConfig(
          serverId = serverId,
          bindHost = ymap.getOrElse("bind_host", "127.0.0.1").toString,
          bindPort = port.get,
          numParsers = Try(ymap("num_parsers").asInstanceOf[Int]).getOrElse(1),
          statsUpdateNumLines = Try(ymap("stats_update_num_lines").toString.toLong).getOrElse(1000L)
        )
      )
    }
  }
}