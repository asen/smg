package com.smule.smgplugins.auth.trustedheader

import com.smule.smg.auth.{User, UsersProvider}
import com.smule.smg.config.SMGConfigService
import com.smule.smgplugins.auth.SMGAuthPluginConfParser
import play.api.mvc.Request

import scala.util.Try

class TrustedHeaderUsersProvider(cfSvc: SMGConfigService, confParser: SMGAuthPluginConfParser)  extends UsersProvider{

  override def configReloaded(): Unit = {
    // Nothing to do
  }

  override def fromRequest[A](request: Request[A]): Option[User] = {
    val thConf = confParser.conf.thConf
    request.headers.get(thConf.handleHeaderName).map { handle =>
      val role = thConf.rolesHeaderName.flatMap { rh =>
        request.headers.get(rh).flatMap(rhv => Try(User.Role.withName(rhv)).toOption)
      }.getOrElse(thConf.defaultRole)
      TrustedHeaderUser(
        handle = handle,
        name = thConf.nameHeaderName.flatMap(nh => request.headers.get(nh)),
        role = role
      )
    }
  }
}
