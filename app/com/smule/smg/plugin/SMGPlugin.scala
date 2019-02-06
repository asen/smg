package com.smule.smg.plugin

import java.util.concurrent.atomic.AtomicBoolean

import com.smule.smg.config.SMGConfIndex
import com.smule.smg.core.{SMGObjectView, SMGPreFetchCmd}
import com.smule.smg.monitor.SMGMonCheck
import com.smule.smg.remote.SMGRemotesApi
import com.smule.smg.GrapherApi
import com.smule.smg.cdash.{CDashConfigItem, CDashItem}

import scala.concurrent.Future

/**
  * Created by asen on 12/3/15.
  *
  * Plugins can be used to extend SMG in various ways including:
  * - define its own object types which do not necessarily conform to the run-external-command-to-get-values model
  *   E.g. for JMX polling its usually better to keep the connections alive vs reconnect very often, that is
  *   implemented in the bundled JMX plugin
  * - Gets its run() method called every interval seconds. In addition to updating objects a plugin can do various
  *   other tasks such as cleanup/maintenance
  * - Plugins can implement graph actions (visible as links near graphs). These in turn can do various things
  *   including custom data visualisations via the htmlContent method (see jsgraph plugin for example)
  * - Plugin can implement monitor checks (via valueChecks method) to extend the available by default
  *   alert-[warn|crit]-[gt[e]|lt[e]|eq] options. E.g. one can check for value anomalies etc. See the mon plugin
  *   for example.
  *
  * A plugin can be any class implementing the SMGPlugin trait with a constructor looking like this:
  *
  * class SMGSomePlugin(val pluginId: String,
  *                     val interval: Int,
  *                     val pluginConfFile: String,
  *                     val smgConfSvc: SMGConfigService
  *                    ) extends SMGPlugin
  *
  * The class name must be set in application.conf together with the plugin id, interval and plugin conf file.
  *
  */

trait SMGPlugin {
  /**
    * The unique plugin id
    */
  val pluginId: String

  /**
    * The plugin interval - how often to call run(). 0 means to not call run() at all.
    */
  val interval: Int

  val showInMenu: Boolean = true

  /**
    * Any custom objectViews the plugin defines.
    * @return - sequence of object views
    */
  def objects: Seq[SMGObjectView] = Seq()

  /**
    * Any relevant indexes the plugin defines.
    * @return - sequence of indexes
    */
  def indexes: Seq[SMGConfIndex] = Seq()

  /**
    * Called periodically as specified by interval
    */
  def run(): Unit = {}

  /**
    * Called during config reload but before the configSvc.config has been updated (and it is an error to access it there)
    * plugin can parse its own config there and build any objects or indexes which will later also be available in
    * configSvc.config
    */
  def reloadConf(): Unit = {}

  /**
    * Called on reload conf after the configSvc.config object has been populated
    */
  def onConfigReloaded(): Unit = {}

  /**
    * Called on system shutdown. Suitable for saving state etc.
    */
  def onShutdown(): Unit = {}

  /**
    * Plugins can define real (or synthetic) pf commands and send monitor messages for these
    * actual plugin objects should have proper prefetch/parents relationships
    * @return
    */
  def preFetches: Map[String, SMGPreFetchCmd] = Map()

  /**
    * Plugins can implement custom logic for checking numeric values extending
    * the built-in alert-(warn,crit)-(gt,lt,eq,...) checks
    * @return
    */
  def valueChecks: Map[String, SMGMonCheck] = Map()

  // XXX TODO need to rethink dependencies (who creates the plugins) to get rid of this
  // Curently these are set by SMGConfigService and the SMGrapher singletons on startup
  private var remotesInst: SMGRemotesApi = _ //null
  def setRemotesApi(remotesApi: SMGRemotesApi): Unit = remotesInst = remotesApi
  def remotes: SMGRemotesApi = remotesInst

  private var smgInst: GrapherApi = _ //null
  def setGrapherApi(grapherApi: GrapherApi): Unit = smgInst = grapherApi
  def smg: GrapherApi = smgInst

  // XXX move these below away from the trait in to a base plugin implementation
  def htmlContent(httpParams: Map[String,String]): String = "This Plugin does not provide html content view: <b>" + pluginId + "</b>"

  /**
    * Whether the plugin page should auto refresh
    */
  val autoRefresh: Boolean = true

  /**
   * Called by plugin remote api data call. To be used for plugin remote API access
   */
  def rawData(httpParams: Map[String,String]): String = ""

  /**
    * Any graph actions the plugin implements
    */
  val actions: Seq[SMGPluginAction] = Seq()

  // primitives to help plugins detect overlapping runs
  private val isRunning = new AtomicBoolean(false)

  /**
    * Check if isRunning is set, i.e. whether plugin is running
    * @return
    */
  def checkRunning: Boolean = isRunning.get()

  /**
    * Set isRunning to true but only if it was false. I.e. use this at the beginning
    * of a "run" (whatever that means in the context of the plugin) to indicate that
    * the run is starting. This acquires a lock (only one caller will get true if multiple)
    * which MUST be released at the end of the run using finished().
    *
    * @return - true if the lock was acquired, false if it was already held by another run.
    */
  def checkAndSetRunning: Boolean = isRunning.compareAndSet(false, true)

  /**
    * Set isRunning to false indicating that the plugin run was complete. This releases the lock
    * acquired by checkAndSetRunning.
    *
    * On should call this only if checkAndSetRunning returned true or
    * in case we want to forcefully reset the state.
    */
  def finished(): Unit = isRunning.set(false)


  /**
    * Extension point for implementing custom dashboad items in plugins
    */
  def cdashItem(confItem: CDashConfigItem): Option[Future[CDashItem]] = None
}
