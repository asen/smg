package controllers.actions

import com.smule.smg.auth.{User, UsersService}
import com.smule.smg.core.SMGLogger
import play.api.mvc.{ActionBuilder, ActionFilter, AnyContent, BodyParsers, Request, Result, Results}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SystemAction @Inject()(val parser: BodyParsers.Default, userSvc: UsersService)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[SystemRequest, AnyContent]{

  private val log = SMGLogger

  override def invokeBlock[A](request: Request[A], block: SystemRequest[A] => Future[Result]): Future[Result] = {
    val userTupl = userSvc.fromSystemRequest(request)
    if (userTupl.isDefined) {
      val userReq = new SystemRequest(userTupl.get._1, userTupl.get._2, request)
      block(userReq)
    } else {
      val msg = s"Authentication failed - system requests require authentication"
      val hdrs = request.headers.headers.map(h => s"${h._1}=${h._2}").mkString(", ")
      log.error(s"SystemAction: $msg. Request headers: $hdrs")
      Future.successful(Results.Forbidden(msg))
    }
  }

  protected def roleFilter(role: User.Role.Value)(implicit ec: ExecutionContext): ActionFilter[SystemRequest] =
    new ActionFilter[SystemRequest] {
      def executionContext: ExecutionContext = ec
      def filter[A](input: SystemRequest[A]): Future[Option[Result]] = Future.successful {
        if (input.user.role < role) {
          val msg = s"System authorization failed - ${role} role required"
          log.error(s"SystemAction: $msg (user has ${input.user.role}), user: ${input.user}")
          Some(Results.Forbidden(msg))
        } else
          None
      }
    }

  private val rootFilter = roleFilter(User.Role.ROOT)
  private val adminFilter = roleFilter(User.Role.ADMIN)
  private val viewerFilter = roleFilter(User.Role.VIEWER)

  def rootAction: ActionBuilder[SystemRequest, AnyContent] = this.andThen(rootFilter)
  def adminAction: ActionBuilder[SystemRequest, AnyContent] = this.andThen(adminFilter)
  def viewAction: ActionBuilder[SystemRequest, AnyContent] = this.andThen(viewerFilter)
}
