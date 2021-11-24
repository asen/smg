package com.smule.smg.auth.anonymous

import com.smule.smg.auth.User

case class AnonymousUser(role: User.Role.Value) extends User {
  override def handle: String = "anonymous"
  override def name: Option[String] = Some(s"Anonymous")

  override def supportsLogout: Boolean = false
  override def supportsLogin: Boolean = true
}

object AnonymousUser {
  val anonymousRoot: AnonymousUser = AnonymousUser(User.Role.ROOT)
  val anonymousAdmin: AnonymousUser = AnonymousUser(User.Role.ADMIN)
  val anonymousViewer: AnonymousUser = AnonymousUser(User.Role.VIEWER)
}
