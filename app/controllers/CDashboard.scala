package controllers

import akka.actor.ActorSystem
import com.smule.smg.GrapherApi
import com.smule.smg.cdash.CDashboardApi
import com.smule.smg.config.SMGConfigService
import controllers.actions.UserAction

import javax.inject.{Inject, Singleton}
import play.api.mvc.{Action, AnyContent, _}

import scala.concurrent.ExecutionContext

@Singleton
class CDashboard @Inject() (actorSystem: ActorSystem,
                            configSvc: SMGConfigService,
                            smg: GrapherApi,
                            cDashSvc: CDashboardApi,
                            userAction: UserAction
                           ) extends InjectedController  {

  implicit private val myEc: ExecutionContext = configSvc.executionContexts.rrdGraphCtx

  def index(): Action[AnyContent] = userAction.viewAction { implicit request =>
    Ok(views.html.cdashList(configSvc))
  }

  def cdash(cdid: String): Action[AnyContent] = userAction.viewAction.async { implicit request =>
    cDashSvc.getDashboardData(cdid).map { cdOpt =>
      if (cdOpt.isEmpty)
        NotFound(s"Dashboard not found: $cdid")
      else {
        Ok(views.html.cdash(configSvc, cdOpt.get))
      }
    }
  }
}
