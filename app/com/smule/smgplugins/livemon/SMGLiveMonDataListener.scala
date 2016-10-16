package com.smule.smgplugins.livemon

import com.smule.smg._

/**
  * Created by asen on 7/5/16.
  */
class SMGLiveMonDataListener(log: SMGPluginLogger) extends SMGDataFeedListener{

  override def receiveObjMsg(msg: SMGDFObjMsg): Unit = {
    log.info("SMGLiveMonDataListener.receive: " + msg)
  }

  override def receiveRunMsg(msg: SMGDFRunMsg): Unit = {
    log.info("SMGLiveMonDataListener.receiveRunMsg: " + msg)
  }

  override def receivePfMsg(msg: SMGDFPfMsg): Unit = {
    log.info("SMGLiveMonDataListener.receivePfMsg: " + msg)
  }
}
