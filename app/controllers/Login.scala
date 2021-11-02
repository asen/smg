package controllers

import com.smule.smg.auth.UsersService
import com.smule.smg.config.SMGConfigService
import play.api.mvc.{Action, AnyContent, InjectedController}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class Login @Inject() (configSvc: SMGConfigService,
                       userSvc: UsersService
                      )(implicit ec: ExecutionContext) extends InjectedController{

  def loginPage(): Action[AnyContent] = Action { implicit request =>
    val redirectUrl = request.queryString.get("url").map(_.head)
    Ok(views.html.login(configSvc, redirectUrl.getOrElse("/")))
  }

  def login(): Action[AnyContent] = Action { implicit request =>
    val params = request.body.asFormUrlEncoded.get
    val handle = params.get("handle").map(_.head)
    val password = params.get("password").map(_.head)
    val redirectUrl = params.get("redirect_url").map(_.head)
    if (handle.isEmpty || password.isEmpty){
      Redirect("/login").flashing("error" -> "User and password are required")
    } else {
      val sessionId = userSvc.newPasswordUserSession(handle.get, password.get)
      if (sessionId.isEmpty) {
        Redirect("/login").flashing("error" -> "Invalid user or password")
      } else {
        Redirect(redirectUrl.getOrElse("/")).
          withSession(configSvc.config.usersConfig.passwordUserCookieKey -> sessionId.get)
      }
    }
  }

  def logout(): Action[AnyContent] = Action { implicit request =>
    Redirect(configSvc.config.usersConfig.loginUrl).withNewSession
  }
}
