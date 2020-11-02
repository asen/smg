package com.smule.smgplugins.jmx

import com.smule.smg.plugin.SMGPluginLogger

import scala.collection.concurrent.TrieMap

/**
  * Created by asen on 4/13/17.
  */
class SMGJmxClient(val log: SMGPluginLogger) {

  private val jmxConnections = TrieMap[String, SMGJmxConnection]()

  private def getJmxConnection(hostPort: String): SMGJmxConnection = {
    // XXX there is a race condition with removeJmxConnection here,
    // however it will only deal with connections unused for long time
    var created = false
    val ret = jmxConnections.getOrElseUpdate(hostPort, {
      created = true
      log.debug(s"Creating new SMGJmxConnection for $hostPort")
      new SMGJmxConnection(hostPort, log, System.currentTimeMillis())
    })
    if (!created)
      ret.lastActiveTsms = System.currentTimeMillis()
    ret
  }

  private def removeJmxConnection(hostPort: String): Unit = {
    try {
      // "best effort"
      val opt = jmxConnections.get(hostPort)
      if (opt.isDefined)
        opt.get.closeJmxMbeanConnection()
      jmxConnections.remove(hostPort)
      log.info(s"Removed SMGJmxConnection for $hostPort")
    } catch { case t: Throwable =>
      log.ex(t, s"Exception from removeJmxConnection($hostPort): ${t.getMessage}")
    }
  }

  def checkJmxConnection(hostPort: String): Option[String] = {
    val conn = getJmxConnection(hostPort)
    conn.checkJmxMbeanConnection
  }

  /**
    * Will throw on error
    * @param hostPort
    * @param objName
    * @param attrNames
    * @return
    */
  def fetchJmxValues(hostPort: String, objName: String, attrNames: Seq[String] ): List[Double] = {
    getJmxConnection(hostPort).fetchJmxValues(objName, attrNames)
  }

  def cleanupObsoleteConnections(maxUnusedSec: Int): Unit = {
    val cutoffTsms: Long = System.currentTimeMillis() - (maxUnusedSec * 1000)
    jmxConnections.toList.foreach { t =>
      if (t._2.lastActiveTsms < cutoffTsms)
        removeJmxConnection(t._1)
    }
  }
}
