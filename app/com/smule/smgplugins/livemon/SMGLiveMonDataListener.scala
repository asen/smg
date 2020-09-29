package com.smule.smgplugins.livemon

import com.smule.smg.core.{SMGDataFeedListener, SMGDataFeedMsgCmd, SMGDataFeedMsgRun, SMGDataFeedMsgVals}
import com.smule.smg.plugin.SMGPluginLogger

/**
  * Created by asen on 7/5/16.
  */
class SMGLiveMonDataListener(log: SMGPluginLogger) extends SMGDataFeedListener{

  override def receiveValuesMsg(msg: SMGDataFeedMsgVals): Unit = {
    log.info("SMGLiveMonDataListener.receive: " + msg)
  }

  override def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit = {
    log.info("SMGLiveMonDataListener.receiveRunMsg: " + msg)
  }

  override def receiveCommandMsg(msg: SMGDataFeedMsgCmd): Unit = {
    log.info("SMGLiveMonDataListener.receiveCommandMsg: " + msg)
  }
}
