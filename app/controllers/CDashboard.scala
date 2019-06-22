package controllers

import akka.actor.ActorSystem
import com.smule.smg.GrapherApi
import com.smule.smg.cdash.CDashboardApi
import com.smule.smg.config.SMGConfigService
import javax.inject.{Inject, Singleton}
import play.api.mvc.{Action, AnyContent, _}

import scala.concurrent.ExecutionContext

@Singleton
class CDashboard @Inject() (actorSystem: ActorSystem,
                            configSvc: SMGConfigService,
                            smg: GrapherApi,
                            cDashSvc: CDashboardApi
                           ) extends InjectedController  {

  implicit private val myEc: ExecutionContext = configSvc.executionContexts.rrdGraphCtx

  def index(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.cdashList(configSvc))
  }

  def cdash(cdid: String): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    cDashSvc.getDashboardData(cdid).map { cdOpt =>
      if (cdOpt.isEmpty)
        NotFound(s"Dashboard not found: $cdid")
      else {
        Ok(views.html.cdash(configSvc, cdOpt.get))
      }
    }
  }
}
