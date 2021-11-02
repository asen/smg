package com.smule.smgplugins.auth.trustedheader

import com.smule.smg.auth.User

case class TrustedHeaderUser(handle: String, name: Option[String], role: User.Role.Value ) extends User {
  override val supportsLogout: Boolean = false
}
