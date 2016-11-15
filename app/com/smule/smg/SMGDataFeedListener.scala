package com.smule.smg

/**
  * Created by asen on 7/5/16.
  */

case class SMGDFObjMsg( ts: Int, obj: SMGObjectUpdate, vals: List[Double], exitCode: Int, errors: List[String])

case class SMGDFPfMsg( ts: Int, pfId: String, interval: Int, objs: Seq[SMGObjectUpdate], exitCode: Int, errors: List[String])

case class SMGDFRunMsg(interval: Int, errors: List[String], pluginId: Option[String] = None) {
  val ts = SMGRrd.tssNow
  val isOverlap = errors.nonEmpty
}


trait SMGDataFeedListener {

  def receiveObjMsg(msg: SMGDFObjMsg): Unit

  def receivePfMsg(msg: SMGDFPfMsg): Unit

  def receiveRunMsg(msg: SMGDFRunMsg): Unit

}
