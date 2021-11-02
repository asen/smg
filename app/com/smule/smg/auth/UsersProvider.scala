package com.smule.smg.auth

import play.api.mvc.Request

trait UsersProvider {
  def configReloaded(): Unit
  def fromRequest[A](request: Request[A]): Option[User]
}
