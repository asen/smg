package com.smule.smg

import java.util.concurrent.atomic.AtomicBoolean

/**
  * Created by asen on 12/3/15.
  */

trait SMGPluginAction {
  val actionId: String
  val name: String
  val pluginId: String
  def actionUrl(ov: SMGObjectView, period: String): String
}

trait SMGPlugin {
  val pluginId: String
  val interval: Int
  def objects: Seq[SMGObjectView]
  def indexes: Seq[SMGConfIndex]
  def run(): Unit
  def reloadConf(): Unit

  /**
    * Plugins can define real (or synthetic) pf commands and send monitor messages for these
    * actual plugin objects should have proper prefetch/parents relationships
    * @return
    */
  def preFetches: Map[String, SMGPreFetchCmd] = Map()

  // XXX TODO need to rethink dependencies (who creates the plugins) to get rid of this
  // Curently these are set by SMGConfigService and the SMGrapher singletons on startup
  private var remotesInst: SMGRemotesApi = null
  def setRemotesApi(remotesApi: SMGRemotesApi): Unit = remotesInst = remotesApi
  def remotes: SMGRemotesApi = remotesInst

  private var smgInst: GrapherApi = null
  def setGrapherApi(grapherApi: GrapherApi): Unit = smgInst = grapherApi
  def smg: GrapherApi = smgInst

  // XXX move these below away from the trait in to a base plugin implementation
  def htmlContent(httpParams: Map[String,String]): String = "This Plugin does not provide html content view: <b>" + pluginId + "</b>"

  val autoRefresh: Boolean = true

  def rawData(httpParams: Map[String,String]): String = ""

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

}

class SMGPluginLogger(pluginId: String) extends SMGLoggerApi {

  private val logger = play.api.Logger(pluginId)

  override def debug(a:Any): Unit = logger.debug(a.toString)

  override def info(a:Any): Unit = logger.info(a.toString)

  override def warn(a:Any): Unit = logger.warn(a.toString)

  override def error(a:Any): Unit = logger.error(a.toString)

  override def ex(ex:Throwable, msg: String = ""): Unit = {
    if (msg != "")
      error(msg)
    error(ex)
    error(ex.getStackTrace.map(ste => ste.toString).mkString(" "))
  }

}


