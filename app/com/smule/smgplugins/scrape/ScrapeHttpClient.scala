package com.smule.smgplugins.scrape

import java.util.concurrent.TimeUnit

import com.smule.smg.core.{SMGFetchException, SMGFileUtil, SMGLoggerApi}
import javax.net.ssl.HostnameVerifier
import okhttp3.{OkHttpClient, Request}

class ScrapeHttpClient(log: SMGLoggerApi) {
  private val okHttp = new OkHttpClient()
  private val unsafeSSLManager = new UnsafeSSLContext()

  def getUrl(url: String, timeoutSec: Int,
             secureTls: Boolean, bearerTokenFile: Option[String]): String = try {
    var myClient = okHttp.newBuilder().
      callTimeout(timeoutSec, TimeUnit.SECONDS)
    if (!secureTls) try {
      val fry = unsafeSSLManager.unsafeSSLContext.getSocketFactory
      myClient = myClient.sslSocketFactory(fry, unsafeSSLManager. unsafeTrustManager)
      import javax.net.ssl.SSLSession
      myClient.hostnameVerifier(new HostnameVerifier {
        override def verify(hostname: String, session: SSLSession): Boolean = true
      })
    } catch { case t: Throwable =>
      val msg = s"Unable to set sslSocketFactory: ${t.getMessage}"
      log.ex(t, msg)
      throw new SMGFetchException(msg)
    }
    var request = new Request.Builder().url(url)
    if (bearerTokenFile.isDefined){
      // TODO cache tokens?
      val bearerToken = SMGFileUtil.getFileContents(bearerTokenFile.get)
      request = request.addHeader("Authorization", s"Bearer $bearerToken")
    }
    val resp = try {
      myClient.build().newCall(request.build()).execute()
    } catch { case t: Throwable =>
      throw new SMGFetchException(s"ScrapeHttpClient.getUrl($url) " +
        s"($timeoutSec): Exception: ${t.getMessage}")
    }
    if (resp.isSuccessful)
      resp.body().string()
    else
      throw new SMGFetchException(s"ScrapeHttpClient.getUrl($url) " +
        s"($timeoutSec): bad response: ${resp.code()} ${resp.toString}")
  } catch {
    case fe: SMGFetchException => throw fe
    case t: Throwable => throw new SMGFetchException(s"ScrapeHttpClient.getUrl($url) " +
      s"($timeoutSec): unexpected error ${t.getMessage}")
  }
}
