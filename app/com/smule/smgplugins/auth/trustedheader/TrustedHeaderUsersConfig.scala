package com.smule.smgplugins.auth.trustedheader

import com.smule.smg.auth.User

case class TrustedHeaderUsersConfig(
                                     enabled: Boolean,
                                     handleHeaderName: String,
                                     rolesHeaderName: Option[String],
                                     nameHeaderName: Option[String],
                                     defaultRole: User.Role.Value
                                   )

object TrustedHeaderUsersConfig {
  val default: TrustedHeaderUsersConfig = TrustedHeaderUsersConfig(
    enabled = false,
    handleHeaderName = "X-SMG-Auth-handle",
    rolesHeaderName = None, //"X-SMG-Auth-role"
    nameHeaderName = None, // "X-SMG-Auth-name"
    defaultRole = User.Role.VIEWER
  )

  def fromConfigGlobals(globals: Map[String, String]):TrustedHeaderUsersConfig = {
    //    - $auth-plugin-trusted-header-enabled: false
    //    - $auth-plugin-trusted-header-handle: "X-SMG-Auth-handle"
    //    - $auth-plugin-trusted-header-name: "X-SMG-Auth-name"
    //    - $auth-plugin-trusted-header-role: "X-SMG-Auth-role"
    //    - $auth-plugin-trusted-header-default-role: admin

    TrustedHeaderUsersConfig(
      enabled = globals.getOrElse("$auth-plugin-trusted-header-enabled", "false") == "true",
      handleHeaderName = globals.getOrElse("$auth-plugin-trusted-header-handle", default.handleHeaderName),
      rolesHeaderName = globals.get("$auth-plugin-trusted-header-role"),
      nameHeaderName = globals.get("$auth-plugin-trusted-header-name"),
      defaultRole = globals.get("$auth-plugin-trusted-header-default-role").
        map(User.roleFromString).getOrElse(default.defaultRole)
    )
  }
}