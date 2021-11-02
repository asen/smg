package com.smule.smg.auth

import java.nio.charset.StandardCharsets
import java.util.Base64

case class RemoteUser(handle: String, name: Option[String], role: User.Role.Value) extends User {
  def toBase64: String = Base64.getEncoder.
    encodeToString(s"$role:$handle:${name.getOrElse("")}".getBytes(StandardCharsets.UTF_8))

  override val supportsLogout: Boolean = false
}

object RemoteUser {
  def fromBase64(s: String) : Option[RemoteUser] = {
    try {
      val decoded = Base64.getDecoder.decode(s)
      val str = new String(decoded, StandardCharsets.UTF_8)
      val arr = str.split(":", 3)
      Some(RemoteUser(
        handle = arr(1),
        role = User.Role.withName(arr(0)),
        name = arr.lift(3).flatMap { s => if (s == "") None else Some(s) }
      ))
    } catch { case t: Throwable =>
      None
    }
  }
}
