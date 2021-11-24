package com.smule.smg.auth

import scala.util.Try

trait User{
  def handle: String
  def name: Option[String]
  def role: User.Role.Value
  //  def flt: Option[SMGFilter]
  def supportsLogout: Boolean
  def supportsLogin: Boolean

  def humanName: String = name.getOrElse(handle)

  def isRoot: Boolean = role == User.Role.ROOT
  def isAdmin: Boolean = role >= User.Role.ADMIN
  def isViewer: Boolean = role >= User.Role.VIEWER

  def asRemoteUser: RemoteUser = RemoteUser(handle, name, role)
}

object User {
  object Role extends Enumeration {
    val VIEWER, ADMIN, ROOT = Value
  }

  def roleFromString(s: String): Role.Value = {
    Try(Role.withName(s.toUpperCase)).getOrElse(Role.VIEWER)
  }
}
