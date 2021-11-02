package com.smule.smg.auth.anonymous

import com.smule.smg.auth.{User, UsersProvider}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.SMGLogger
import play.api.mvc.Request

class AnonymousUsersProvider(cfSvc: SMGConfigService) extends UsersProvider {
  private val log = SMGLogger

  override def configReloaded(): Unit = {
    //nothing to do
  }

  override def fromRequest[A](request: Request[A]): Option[User] = {
    val uconf = cfSvc.config.usersConfig
    val ret = if (uconf.anonymousRoot) {
      Some(AnonymousUser.anonymousRoot)
    } else if (uconf.anonymousAdmin) {
      Some(AnonymousUser.anonymousAdmin)
    } else if (uconf.anonymousViewer) {
      Some(AnonymousUser.anonymousViewer)
    } else None
    if (ret.isDefined) {
      log.debug(s"AnonymousUsersProvider: Granted anonymous level access with role ${ret.get.role}")
    }
    ret
  }
}
