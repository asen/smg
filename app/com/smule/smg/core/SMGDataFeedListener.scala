package com.smule.smg.core

import com.smule.smg.rrd.SMGRrd

/**
  * Created by asen on 7/5/16.
  */

trait SMGDataFeedListener {

  def receiveObjMsg(msg: SMGDataFeedMsgObj): Unit

  def receivePfMsg(msg: SMGDataFeedMsgPf): Unit

  def receiveRunMsg(msg: SMGDataFeedMsgRun): Unit

}
