package controllers.actions

import com.smule.smg.auth.User
import play.api.mvc.{Request, WrappedRequest}

class UserRequest[A](val user: User, request: Request[A]) extends WrappedRequest[A](request)
