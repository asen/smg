package com.smule.smg.core

case class SMGDataFeedMsgObj(ts: Int, obj: SMGObjectUpdate, vals: List[Double], exitCode: Int, errors: List[String])
