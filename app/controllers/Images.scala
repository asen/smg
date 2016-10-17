package controllers

import java.io.File
import javax.inject.Inject

import com.smule.smg.SMGLogger
import play.Play
import play.api.mvc.{Action, AnyContent, Controller}

/**
  * Created by asen on 10/12/16.
  */
class Images  @Inject() () extends Controller {
  val log = SMGLogger

  def at(rootPath: String, file: String): Action[AnyContent] = Action { request =>
    if (file.contains(File.pathSeparator)) {
      log.error("Refusing to serve files outside my root: " + file)
      NotFound
    } else {
      val fileToServe = new File(Play.application.getFile(rootPath), file)
      if (fileToServe.exists) {
        Ok.sendFile(fileToServe, inline = true).withHeaders(CACHE_CONTROL -> "max-age=30")
      } else {
        NotFound
      }
    }
  }

}
