package controllers

import com.smule.smg.config.SMGConfigService

import java.io.File
import com.smule.smg.core.SMGLogger
import controllers.actions.UserAction

import javax.inject.Inject
import play.Environment
import play.api.mvc.{Action, AnyContent, InjectedController}

import scala.concurrent.ExecutionContext

/**
  * Created by asen on 10/12/16.
  */
class Images  @Inject() (env: Environment,
                         configSvc: SMGConfigService,
                         userAction: UserAction
                        )(implicit ec: ExecutionContext) extends InjectedController {
  val log = SMGLogger

  def at(rootPath: String, file: String): Action[AnyContent] = userAction.viewAction { request =>
    if (file.contains(File.pathSeparator)) {
      log.error("Refusing to serve files outside my root: " + file)
      NotFound
    } else {
      val fileToServe = new File(env.getFile(rootPath), file)
      if (fileToServe.exists) {
        Ok.sendFile(fileToServe, inline = true).
          withHeaders(configSvc.smgImageHeaders.toSeq:_*)
      } else {
        NotFound
      }
    }
  }

}
