package com.smule.smg.core

import com.smule.smg.rrd.SMGRrd

/**
  * Created by asen on 7/5/16.
  */

trait SMGDataFeedListener {

  def receiveValuesMsg(msg: SMGDataFeedMsgVals): Unit

  def receiveCommandMsg(msg: SMGDataFeedMsgCmd): Unit

  def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit
}
