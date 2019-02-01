package com.smule.smgplugins.livemon

import com.smule.smg._
import com.smule.smg.core.{SMGDataFeedListener, SMGDataFeedMsgObj, SMGDataFeedMsgPf, SMGDataFeedMsgRun}
import com.smule.smg.plugin.SMGPluginLogger

/**
  * Created by asen on 7/5/16.
  */
class SMGLiveMonDataListener(log: SMGPluginLogger) extends SMGDataFeedListener{

  override def receiveObjMsg(msg: SMGDataFeedMsgObj): Unit = {
    log.info("SMGLiveMonDataListener.receive: " + msg)
  }

  override def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit = {
    log.info("SMGLiveMonDataListener.receiveRunMsg: " + msg)
  }

  override def receivePfMsg(msg: SMGDataFeedMsgPf): Unit = {
    log.info("SMGLiveMonDataListener.receivePfMsg: " + msg)
  }
}
