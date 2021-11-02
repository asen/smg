package com.smule.smg.auth.userpass

import com.smule.smg.auth.UsersProvider
import com.smule.smg.config.{SMGConfigService, SMGUsersConfig}
import com.smule.smg.auth.User
import com.smule.smg.core.SMGLogger
import play.api.mvc.Request

class PasswordUsersProvider(cfSvc: SMGConfigService) extends UsersProvider {
  private val log = SMGLogger

  private val sessionManager: SMGSessionManager = new SMGSessionManager()
  private val passwordValidator: PasswordValidator = new PasswordValidator()

  private def usersConf: SMGUsersConfig = cfSvc.config.usersConfig
  private def usersMap: Map[String, PasswordUser] = usersConf.passwordUsersMap

  override def fromRequest[A](request: Request[A]): Option[User] = {
    val sessToken = request.session.get(usersConf.passwordUserCookieKey)
    sessToken.flatMap { tkn =>
      sessionManager.userIdFromSessionId(tkn).flatMap { handle =>
        usersMap.get(handle)
      }
    }
  }

  override def configReloaded(): Unit = {
    sessionManager.purgeSessions(usersMap.values.toSeq)
  }
  
  def loginUserPass(handle: String, password: String): Option[String] = {
    val userOpt = usersMap.get(handle)
    userOpt.flatMap { user =>
      if (passwordValidator.validatePassword(handle, password, user.passwordHash)) {
        log.info(s"PASSWORD_LOGIN: Successfull login for user $handle (${user.role})")
        Some(sessionManager.newSessionId(user, usersConf.sessionTtlMs))
      } else {
        log.error(s"PASSWORD_LOGIN: Failed login for user $handle (${user.role})")
        None
      }
    }
  }
}
