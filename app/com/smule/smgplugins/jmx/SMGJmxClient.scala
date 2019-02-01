package com.smule.smgplugins.jmx

import com.smule.smg._
import com.smule.smg.plugin.SMGPluginLogger

import scala.collection.concurrent.TrieMap

/**
  * Created by asen on 4/13/17.
  */
class SMGJmxClient(val log: SMGPluginLogger) {

  private val jmxConnections = TrieMap[String, SMGJmxConnection]()

  private def getJmxConnection(hostPort: String): SMGJmxConnection = {
    // trying to avoid synchronization
    if (!jmxConnections.contains(hostPort)) {
      jmxConnections.putIfAbsent(hostPort, {
        log.debug(s"Creating new SMGJmxConnection for $hostPort")
        new SMGJmxConnection(hostPort, log)
      })
    }
    // XXX there is a race condition with removeJmxConnection here, however
    // it can never happen as removeJmxConnection is only called
    // at the end of the JMX run
    jmxConnections(hostPort)
  }

  def removeJmxConnection(hostPort: String): Unit = {
    // "best effort"
    val opt = jmxConnections.get(hostPort)
    if (opt.isDefined)
      opt.get.closeJmxMbeanConnection()
    jmxConnections.remove(hostPort)
    log.info(s"Removed SMGJmxConnection for $hostPort")
  }

  def checkJmxConnection(hostPort: String): Option[String] = {
    getJmxConnection(hostPort).checkJmxMbeanConnection
  }

  /**
    * Will throw on error
    * @param hostPort
    * @param objName
    * @param attrNames
    * @return
    */
  def fetchJmxValues(hostPort: String, objName: String, attrNames: List[String] ): List[Double] = {
    getJmxConnection(hostPort).fetchJmxValues(objName, attrNames)
  }
}
