package com.smule.smg.auth.userpass

import com.smule.smg.auth.User

case class PasswordUser(
                       handle: String,
                       name: Option[String],
                       role: User.Role.Value,
                       passwordHash: String
                       //, customSessionTtl
                     ) extends User {
  override val supportsLogout: Boolean = true
}

//object PasswordUser {
//  val anonymousViewer: User = PasswordUser("anonymous_viewer", Some("Anonymous Viewer"), User.Role.VIEWER, "")
//  val anonymousAdmin: User = PasswordUser("anonymous_admin", Some("Anonymous Admin"), User.Role.ADMIN, "")
//  val anonymousRoot: User = PasswordUser("anonymous_root", Some("Anonymous Root"), User.Role.ROOT, "")
//}
