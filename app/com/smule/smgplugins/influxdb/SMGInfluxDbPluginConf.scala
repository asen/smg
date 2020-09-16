package com.smule.smgplugins.influxdb

case class SMGInfluxDbPluginConf(
                                dbConf: Option[InfluxDbConf],
                                stripGroupIndexSuffix: Boolean
                                ) {
  val writesEnabled: Boolean = dbConf.nonEmpty
}

object SMGInfluxDbPluginConf {
  private val DEFAULT_URL_PATH = "/api/v2/write?bucket=smg_db&precision=s"
  private val DEFAULT_TIMEOUT_MS = 30000L
  private val DEFAULT_BATCH_SIZE = 1000
  private val DEFAULT_PROTO = "http"

  val empty: SMGInfluxDbPluginConf = SMGInfluxDbPluginConf(dbConf = None, stripGroupIndexSuffix = true)

  def fromMap(src: Map[String, String]): SMGInfluxDbPluginConf = {
    if (src.contains("write_host_port")){
      SMGInfluxDbPluginConf(
        dbConf = Some(InfluxDbConf(
          writeHostPort = src("write_host_port"),
          writeUrlPath = src.getOrElse("write_url_path", DEFAULT_URL_PATH),
          writeTimeoutMs = src.get("write_timeout_ms").map(_.toLong).getOrElse(DEFAULT_TIMEOUT_MS),
          writeProto = src.getOrElse("write_proto", DEFAULT_PROTO),
          writeBatchSize = src.get("write_batch_size").map(_.toInt).getOrElse(DEFAULT_BATCH_SIZE)
        )),
        stripGroupIndexSuffix = src.getOrElse("strip_group_index", "true") != "false"
      )
    } else empty
  }
}
