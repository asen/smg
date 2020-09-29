package com.smule.smg.core

case class SMGDataFeedMsgCmd(ts: Int,
                             cmdId: String,
                             interval: Int,
                             objs: Seq[SMGObjectUpdate],
                             exitCode: Int,
                             errors: List[String],
                             pluginId: Option[String]
                     )

