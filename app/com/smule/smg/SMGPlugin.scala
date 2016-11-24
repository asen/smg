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
  private var remotesInst: SMGRemotesApi = null
  def setRemotesApi(remotesApi: SMGRemotesApi) = remotesInst = remotesApi
  def remotes = remotesInst

  private var smgInst: GrapherApi = null
  def setGrapherApi(grapherApi: GrapherApi) = smgInst = grapherApi
  def smg = smgInst

  // XXX move these below away from the trait in to a base plugin implementation
  def htmlContent(httpParams: Map[String,String]): String = "This Plugin does not provide html content view: <b>" + pluginId + "</b>"

  val autoRefresh: Boolean = true

  def rawData(httpParams: Map[String,String]): String = ""

  val actions: Seq[SMGPluginAction] = Seq()

  private val isRunning = new AtomicBoolean(false)

  def checkRunning = isRunning.get()

  def checkAndSetRunning: Boolean = isRunning.compareAndSet(false, true)

  def finished(): Unit = isRunning.set(false)

}

class SMGPluginLogger(pluginId: String) {

  private val logger = play.api.Logger(pluginId)

  def debug(a:Any) = logger.debug(a.toString)

  def info(a:Any) = logger.info(a.toString)

  def warn(a:Any) = logger.warn(a.toString)

  def error(a:Any) = logger.error(a.toString)

  def ex(ex:Throwable, msg: String = "") = {
    if (msg != "")
      error(msg)
    error(ex)
    error(ex.getStackTrace.map(ste => ste.toString).mkString(" "))
  }

}


