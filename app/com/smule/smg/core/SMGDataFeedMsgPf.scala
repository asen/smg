package com.smule.smg.core

case class SMGDataFeedMsgPf(ts: Int,
                            pfId: String,
                            interval: Int,
                            objs: Seq[SMGObjectUpdate],
                            exitCode: Int, errors: List[String],
                            pluginId: Option[String]
                     )

