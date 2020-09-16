package com.smule.smgplugins.influxdb

case class InfluxDbConf(
                       writeHostPort: String,
                       writeUrlPath: String,
                       writeTimeoutMs: Long = 30000,
                       writeBatchSize: Int,
                       writeProto: String = "http"
                       )
