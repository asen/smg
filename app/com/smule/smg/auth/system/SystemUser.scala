package com.smule.smg.auth.system

import com.smule.smg.auth.User

case class SystemUser(handle: String) extends User {

  override def name: Option[String] = None

  override def role: User.Role.Value = User.Role.ROOT

  override val supportsLogout: Boolean = false
}
