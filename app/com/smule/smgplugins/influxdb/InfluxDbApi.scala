package com.smule.smgplugins.influxdb

import java.io.IOException
import java.util.concurrent.TimeUnit

import com.smule.smg.core.SMGLoggerApi
import okhttp3.{Call, Callback, MediaType, OkHttpClient, Request, RequestBody, Response}

class InfluxDbApi(confParser: SMGInfluxDbPluginConfParser, log: SMGLoggerApi) {
  private def dbConf = confParser.conf.dbConf.get
  private def influxDbUrl =
    dbConf.writeProto + "://" + dbConf.writeHostPort + dbConf.writeUrlPath

  private val httpClient = new OkHttpClient()

  def writeBatchAsync(batch: List[InfluxDbRecord]): Unit = {
    val myUrl = influxDbUrl
    val myClient = httpClient.newBuilder().
      callTimeout(dbConf.writeTimeoutMs, TimeUnit.MILLISECONDS).build()
    val body = RequestBody.create(MediaType.get("text/plain"), batch.map(_.line).mkString("\n"))
    val request = new Request.Builder().url(myUrl).post(body).build()
    myClient.newCall(request).enqueue(new Callback {
      override def onFailure(call: Call, e: IOException): Unit = {
        log.ex(e, s"InfluxDbApi: Failed to write batch to InfluxDb ($myUrl) batchSize=${batch.size}")
      }
      override def onResponse(call: Call, response: Response): Unit = {
        log.debug(s"InfluxDbApi: Successfully written ${batch.size} records to $myUrl")
        try {
          response.body().close()
        } catch { case t: Throwable =>
          log.warn(s"InfluxDbApi: Failed to close response body from $myUrl : ${t.getMessage}")
        }
      }
    })
  }
}
