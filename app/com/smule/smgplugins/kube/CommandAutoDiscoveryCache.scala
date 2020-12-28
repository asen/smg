package com.smule.smgplugins.kube

import com.smule.smg.core.SMGLoggerApi
import com.smule.smgplugins.kube.SMGKubeClient.KubeNsObject

import java.util.Date
import scala.collection.concurrent.TrieMap

class CommandAutoDiscoveryCache(log: SMGLoggerApi) {

  // command -> (nsobj, last executed (good or bad), desc for bad)
  private val knownGoodServiceCommands = TrieMap[String,(KubeNsObject, Long)]()
  private val knownBadServiceCommands = TrieMap[String,(KubeNsObject, Long, String)]()

  case class AutoDiscoveredCommandStatus(nsObj: KubeNsObject, tsms: Long,
                                         command: String, reason: Option[String]){
    def tsStr: String = new Date(tsms).toString
    private def reasonOrOk = reason.map("ERROR: " + _).getOrElse("OK")
    def inspect: String = s"${nsObj.namespace}/${nsObj.name}: $command (ts=$tsStr) status=$reasonOrOk"
  }

  def lastGoodRunTs(command: String): Option[Long] = {
    val ret = knownGoodServiceCommands.get(command)
    if (ret.isDefined)
      knownGoodServiceCommands.put(command, (ret.get._1,System.currentTimeMillis()))
    ret.map(_._2)
  }

  def lastBadRunTs(command: String): Option[Long] = knownBadServiceCommands.get(command).map(_._2)

  def recordBad(command: String, kubeNsObject: KubeNsObject, reason: String): Unit =
    knownBadServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis(), reason))

  def recordGood(command: String, kubeNsObject: KubeNsObject): Unit = {
    knownGoodServiceCommands.put(command, (kubeNsObject, System.currentTimeMillis()))
    knownBadServiceCommands.remove(command)
  }

  def listAutoDiscoveredCommands: Seq[AutoDiscoveredCommandStatus] = {
    val good = knownGoodServiceCommands.toSeq.sortBy { x =>
      (x._2._1.namespace, x._2._1.name)
    }.map { x =>
      AutoDiscoveredCommandStatus(nsObj = x._2._1, tsms = x._2._2, command = x._1, reason = None)
    }
    val bad = knownBadServiceCommands.toSeq.sortBy { x =>
      (x._2._1.namespace, x._2._1.name)
    }.map { x =>
      AutoDiscoveredCommandStatus(nsObj = x._2._1, tsms = x._2._2, command = x._1, reason = Some(x._2._3))
    }
    good ++ bad
  }

  def cleanupOld(maxAge: Long): Unit = {
    val cutoffTs = System.currentTimeMillis() - maxAge
    knownGoodServiceCommands.toSeq.filter(t => t._2._2 < cutoffTs).foreach { t =>
      log.info(s"CommandAutoDiscoveryCache.cleanupOld (good): ${t._2._1.name} ${t._1}")
      knownGoodServiceCommands.remove(t._1)
    }
    knownBadServiceCommands.toSeq.filter(t => t._2._2 < cutoffTs).foreach { t =>
      log.info(s"CommandAutoDiscoveryCache.cleanupOld (bad): ${t._2._1.name} ${t._1}")
      knownBadServiceCommands.remove(t._1)
    }
  }
}
