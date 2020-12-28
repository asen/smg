package com.smule.smgplugins.kube

import com.smule.smgplugins.kube.SMGKubeClient.KubeNsObject

import java.util.Date

case class AutoDiscoveredCommandStatus(nsObj: KubeNsObject, tsms: Long,
                                       command: String, reason: Option[String]){
  def tsStr: String = new Date(tsms).toString
  private def reasonOrOk = reason.map("ERROR: " + _).getOrElse("OK")
  def inspect: String = s"${nsObj.namespace}/${nsObj.name}: $command (ts=$tsStr) status=$reasonOrOk"
}
