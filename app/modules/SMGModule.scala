package modules

import com.google.inject.AbstractModule
import com.smule.smg._
import com.smule.smg.cdash.{CDashboardApi, CDashboardSvc}
import com.smule.smg.config.{SMGConfigService, SMGConfigServiceImpl}
import com.smule.smg.core.{ExecutionContexts, SMGExecutionContexts, SMGScheduler, SMGSchedulerApi}
import com.smule.smg.monitor._
import com.smule.smg.notify.{SMGMonNotifyApi, SMGMonNotifySvc}
import com.smule.smg.remote.{SMGRemotes, SMGRemotesApi}
import com.smule.smg.search.{SMGSearchCache, SMGSearchCacheImpl}
import play.api.{Configuration, Environment}

/**
 * Created by asen on 11/10/15.
 */

/**
  * Play Guice bindings for interfaces
  * @param environment
  * @param configuration
  */
class SMGModule(environment: Environment, configuration: Configuration) extends  AbstractModule {

  override def configure(): Unit = {
    bind(classOf[ExecutionContexts]).to(classOf[SMGExecutionContexts])
    bind(classOf[SMGConfigService]).to(classOf[SMGConfigServiceImpl])
    bind(classOf[SMGRemotesApi]).to(classOf[SMGRemotes])
    bind(classOf[SMGSearchCache]).to(classOf[SMGSearchCacheImpl])
    bind(classOf[GrapherApi]).to(classOf[SMGrapher])
    bind(classOf[SMGSchedulerApi]).to(classOf[SMGScheduler])
    bind(classOf[SMGMonitorLogApi]).to(classOf[SMGMonitorLog])
    bind(classOf[SMGMonNotifyApi]).to(classOf[SMGMonNotifySvc])
    bind(classOf[SMGMonitorApi]).to(classOf[SMGMonitor])
    bind(classOf[CDashboardApi]).to(classOf[CDashboardSvc])
  }
}
