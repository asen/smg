package com.smule.smg.auth.system

import com.smule.smg.auth.{User, UsersProvider}
import com.smule.smg.config.{SMGConfigService, SMGUsersConfig}
import com.smule.smg.core.SMGLogger
import play.api.mvc.Request

class SystemUsersProvider(cfSvc: SMGConfigService) extends UsersProvider {
  private val log = SMGLogger

  override def configReloaded(): Unit = {
    // nothing to do ..
  }

  override def fromRequest[A](request: Request[A]): Option[User] = {
    val myConf = cfSvc.config.usersConfig

    lazy val authHeaderName = myConf.systemAuthHeader.getOrElse(SMGUsersConfig.defaultAuthorizationHeader)
    lazy val authHeader = request.headers.get(authHeaderName)
    lazy val bearerToken = authHeader.map { s =>
      val arr = s.split("\\s+", 2)
      if (arr(0).toLowerCase == "bearer") {
        arr(1)
      } else s
    }
    lazy val xffHeaderName = myConf.systemXffHeader.getOrElse(SMGUsersConfig.defaultHffHeader)
    lazy val remoteAddresses = (Seq(request.remoteAddress) ++
      request.headers.get(xffHeaderName).map(s => s.split("\\s+").toSeq).getOrElse(Seq())).filter { ip =>
      !myConf.localhostAddresses.exists(_.isInRange(ip))
    }

    val ret = if (myConf.systemSecretRootTokens.nonEmpty && bearerToken.isDefined &&
      myConf.systemSecretRootTokens.contains(bearerToken.get)) {
      Some(SystemUser("system_token"))
    } else if (remoteAddresses.nonEmpty && myConf.systemAllowedNetworkRanges.exists(sn =>
      remoteAddresses.forall(ip => sn.isInRange(ip)))) {
      Some(SystemUser("system_netrange"))
    } else if (myConf.systemAllowLocalhost && remoteAddresses.isEmpty) {
       Some(SystemUser("system_local"))
    } else None
    if (ret.isDefined) {
      log.debug(s"SystemUsersProvider: Granted system level access to ${ret.get.handle}")
    }
//    else {
//      log.warn(s"SystemUsersProvider: no system level access: $remoteAddresses")
//    }
    ret
  }
}
