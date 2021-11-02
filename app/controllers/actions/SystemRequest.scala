package controllers.actions

import com.smule.smg.auth.User
import play.api.mvc.{Request, WrappedRequest}

class SystemRequest[A](val systemUser: User,
                       val onBehalfOf: Option[User],
                       request: Request[A]) extends WrappedRequest[A](request) {
  lazy val user: User = onBehalfOf.getOrElse(systemUser)
}