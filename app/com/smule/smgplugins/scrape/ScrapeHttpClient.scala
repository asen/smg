package com.smule.smgplugins.scrape

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import com.smule.smg.core.{SMGFetchException, SMGFileUtil, SMGLoggerApi}
import javax.net.ssl.{SSLContext, SSLEngine, X509ExtendedTrustManager}
import okhttp3.{OkHttpClient, Request}

class ScrapeHttpClient(log: SMGLoggerApi) {
  private val okHttp = new OkHttpClient()


  private def  getUnsafeTrustManager: X509ExtendedTrustManager = new X509ExtendedTrustManager() {
    override def checkClientTrusted(chain: Array[X509Certificate],
                                    authType: String, socket: Socket): Unit = {}

    override def checkServerTrusted(chain: Array[X509Certificate],
                                    authType: String, socket: Socket): Unit = {}

    override def checkClientTrusted(chain: Array[X509Certificate],
                                    authType: String, engine: SSLEngine): Unit = {}

    override def checkServerTrusted(chain: Array[X509Certificate],
                                    authType: String, engine: SSLEngine): Unit = {}

    override def checkClientTrusted(chain: Array[X509Certificate],
                                    authType: String): Unit = {}

    override def checkServerTrusted(chain: Array[X509Certificate],
                                    authType: String): Unit = {}

    override def getAcceptedIssuers: Array[X509Certificate] = null
  }

  private lazy val unsafeTrustManager = getUnsafeTrustManager

  private def getUnsafeSSLContext = {
    // Create a trust manager that does not validate certificate chains
    val sc: SSLContext = SSLContext.getInstance("SSL")
    sc.init(null, Array(unsafeTrustManager), new SecureRandom)
    sc
  }

  private lazy val unsafeSSLContext = getUnsafeSSLContext

  def getUrl(url: String, timeoutSec: Int,
             secureTls: Boolean, bearerTokenFile: Option[String]): String = try {
    var myClient = okHttp.newBuilder().
      callTimeout(timeoutSec, TimeUnit.SECONDS)
    if (!secureTls){
      myClient = myClient.sslSocketFactory(unsafeSSLContext.getSocketFactory, unsafeTrustManager)
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
