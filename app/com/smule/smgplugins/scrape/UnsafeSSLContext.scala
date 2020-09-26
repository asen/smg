package com.smule.smgplugins.scrape

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate

import javax.net.ssl.{SSLContext, SSLEngine, X509ExtendedTrustManager}

class UnsafeSSLContext() {
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

    override def getAcceptedIssuers: Array[X509Certificate] = Array()
  }

  val unsafeTrustManager: X509ExtendedTrustManager = getUnsafeTrustManager

  private def getUnsafeSSLContext = {
    // Create a trust manager that does not validate certificate chains
    val sc: SSLContext = SSLContext.getInstance("SSL")
    sc.init(null, Array(unsafeTrustManager), new SecureRandom)
    sc
  }

  val unsafeSSLContext: SSLContext = getUnsafeSSLContext

}
