package com.smule.smgplugins.influxdb

case class SMGInfluxDbPluginConf(
                                  enabled: Boolean,
                                  dbConf: InfluxDbConf,
                                  stripGroupIndexSuffix: Boolean
                                ) {
  val writesEnabled: Boolean = enabled
}

object SMGInfluxDbPluginConf {
  private val DEFAULT_HOST_PORT = "localhost:8086"

  private val DEFAULT_URL_PATH = "/api/v2/write?bucket=smg_db&precision=s"
  private val DEFAULT_TIMEOUT_MS = 30000L
  private val DEFAULT_BATCH_SIZE = 1000
  private val DEFAULT_PROTO = "http"

  private val defaultDbConf = InfluxDbConf(
    DEFAULT_HOST_PORT,
    DEFAULT_URL_PATH,
    DEFAULT_TIMEOUT_MS,
    DEFAULT_BATCH_SIZE,
    DEFAULT_PROTO
  )

  val empty: SMGInfluxDbPluginConf = SMGInfluxDbPluginConf(
    enabled = false,
    dbConf = defaultDbConf,
    stripGroupIndexSuffix = true)

  def fromMap(src: Map[String, String]): SMGInfluxDbPluginConf = {
    if (src.contains("write_host_port")){
      SMGInfluxDbPluginConf(
        enabled = true,
        dbConf = InfluxDbConf(
          writeHostPort = src("write_host_port"),
          writeUrlPath = src.getOrElse("write_url_path", DEFAULT_URL_PATH),
          writeTimeoutMs = src.get("write_timeout_ms").map(_.toLong).getOrElse(DEFAULT_TIMEOUT_MS),
          writeProto = src.getOrElse("write_proto", DEFAULT_PROTO),
          writeBatchSize = src.get("write_batch_size").map(_.toInt).getOrElse(DEFAULT_BATCH_SIZE)
        ),
        stripGroupIndexSuffix = src.getOrElse("strip_group_index", "true") != "false"
      )
    } else empty
  }
}
