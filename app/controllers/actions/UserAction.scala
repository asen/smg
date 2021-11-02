package controllers.actions

import com.smule.smg.auth.{User, UsersService}
import com.smule.smg.core.SMGLogger
import play.api.mvc.{ActionBuilder, ActionFilter, AnyContent, BodyParsers, Request, Result, Results}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UserAction @Inject()(val parser: BodyParsers.Default, userSvc: UsersService)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[UserRequest, AnyContent] {

  private val log = SMGLogger

  override def invokeBlock[A](request: Request[A], block: UserRequest[A] => Future[Result]): Future[Result] = {
    val user = userSvc.fromRequest(request)
    if (user.isDefined) {
      val userReq = new UserRequest(user.get, request)
      block(userReq)
    } else {
      Future.successful(Results.Redirect(userSvc.loginUrl, Map("url" -> Seq(request.uri))))
    }
  }

  protected def roleFilter(role: User.Role.Value)(implicit ec: ExecutionContext): ActionFilter[UserRequest] =
    new ActionFilter[UserRequest] {
      def executionContext: ExecutionContext = ec
      def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful {
        if (input.user.role < role) {
          val msg = s"Authorization failed - ${role} role required (user has ${input.user.role})"
          log.error(s"UserAction: $msg")
          Some(Results.Forbidden(msg))
        } else
          None
      }
    }

//  private val rootFilter = roleFilter(User.Role.ROOT)
  private val adminFilter = roleFilter(User.Role.ADMIN)
  private val viewerFilter = roleFilter(User.Role.VIEWER)

//  def rootAction: ActionBuilder[UserRequest, AnyContent] = this.andThen(rootFilter)
  def adminAction: ActionBuilder[UserRequest, AnyContent] = this.andThen(adminFilter)
  def viewAction: ActionBuilder[UserRequest, AnyContent] = this.andThen(viewerFilter)
}
