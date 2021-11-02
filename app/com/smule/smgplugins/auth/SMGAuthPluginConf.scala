package com.smule.smgplugins.auth

import com.smule.smg.auth.User
import com.smule.smgplugins.auth.trustedheader.TrustedHeaderUsersConfig

case class SMGAuthPluginConf(
                              pluginRoleAccess: User.Role.Value,
                              thConf: TrustedHeaderUsersConfig
                            ) {

}

object SMGAuthPluginConf {
  val default: SMGAuthPluginConf = SMGAuthPluginConf(
    pluginRoleAccess = User.Role.ADMIN,
    thConf = TrustedHeaderUsersConfig.default
  )

  def fromConfigGlobals(globals: Map[String, String]): SMGAuthPluginConf = {
//    - $auth-plugin-role-access: admin
    SMGAuthPluginConf(
      pluginRoleAccess = globals.get("$auth-plugin-role-access").map(User.roleFromString).
        getOrElse(default.pluginRoleAccess),
      thConf = TrustedHeaderUsersConfig.fromConfigGlobals(globals)
    )
  }
}