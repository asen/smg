package com.smule.smg.auth.userpass

import com.smule.smg.auth.User

import java.util.UUID
import scala.collection.concurrent.TrieMap

class SMGSessionManager() {

  private case class SessionToken(token: String, userId: String, expiresAtMs: Long)
  private val sessionsMap = TrieMap[String, SessionToken]()

  def newSessionId(user: User, sessionTtlMs: Long): String = {
    val token = UUID.randomUUID().toString
    val expiresAtMs = System.currentTimeMillis() + sessionTtlMs
    sessionsMap.put(token, SessionToken(token, user.handle, expiresAtMs))
    token
  }

  def userIdFromSessionId(sessionId: String): Option[String] = {
    val ret = sessionsMap.get(sessionId)
    if (ret.isDefined) {
      if (ret.get.expiresAtMs > System.currentTimeMillis()) {
        Some(ret.get.userId)
      } else {
        sessionsMap.remove(sessionId)
        None
      }
    } else None
  }

  def purgeSessions(allUsers: Seq[User]): Unit = {
    val handlesSet = allUsers.map(_.handle).toSet
    sessionsMap.values.foreach { case stkn =>
      if (!handlesSet.contains(stkn.userId) || (stkn.expiresAtMs < System.currentTimeMillis()))
        sessionsMap.remove(stkn.token)
    }
  }
}
