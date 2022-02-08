package com.smule.smgplugins.syslog.server

object SyslogActorMessages {
  case class ClientConnectedMsg(clientAddrStr: String)
  case class ClientDisconnectedMsg(clientAddrStr: String)
  case class UpdateConnectionStatsMsg(clientAddrStr: String,
                                      newMergedLines: Long,
                                      newHits: Long,
                                      newForwardedHits: Long,
                                      newLastHitTs: Long)
  case class UpdateLinesMsg(newLines: Long)

  case class GetStatusMsg()
  case class LogStatusMsg()
}

