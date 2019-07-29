package com.smule.smgplugins.jmx

import akka.actor.Props
import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.{SMGDataFeedMsgRun, SMGObjectView, SMGPreFetchCmd, SMGRunStats}
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smg.rrd.SMGRrd
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * TODO
  */

class SMGJmxPlugin (val pluginId: String,
                    val interval: Int,
                    val pluginConfFile: String,
                    val smgConfSvc: SMGConfigService
                   ) extends SMGPlugin {

  override val showInMenu: Boolean = false

  override def objects: Seq[SMGObjectView] = jmxObjects.synchronized(jmxObjects)

  override val autoRefresh: Boolean = false

  val log = new SMGPluginLogger(pluginId)

  private var topLevelConfMap = Map[String,Object]()

  private var jmxObjects = List[SMGJmxObject]()

  private var jmxObjectIndexes = List[SMGConfIndex]()

  private var jmxPreFetches = Map[String, SMGPreFetchCmd]()

  override def preFetches: Map[String, SMGPreFetchCmd] = jmxPreFetches

  private val confParser = new SMGJmxConfigParser(pluginId, smgConfSvc, log)

  private var rmiSocketTimeoutMillis = 0 // set to 0 to force initialization

  private val jmxClient = new SMGJmxClient(log)

  val runCounterName = s"SMGJmxPlugin.$pluginId"

  // XXX we don't want to leak connections on config changes
  // so we close all unused, but only at the end of the run
  // so on config change - just record these
  private val obsoleteHostPorts = mutable.Set[String]()

  override def reloadConf(): Unit = {
    try {
      val confTxt = smgConfSvc.sourceFromFile(pluginConfFile)
      val yaml = new Yaml();
      val newMap = yaml.load(confTxt).asInstanceOf[java.util.Map[String, Object]].asScala
      topLevelConfMap.synchronized {
        topLevelConfMap = newMap(pluginId).asInstanceOf[java.util.Map[String, Object]].asScala.toMap
      }
      var newIndexes = List[SMGConfIndex]()
      jmxObjects.synchronized{
        val t = confParser.parseObjects(interval, topLevelConfMap)
        val prevJmxObjects = jmxObjects
        jmxObjects = t._1
        jmxPreFetches = t._2
        newIndexes = confParser.buildIndexes(jmxObjects)
        // record hostPorts which were present in the old conf but are no longer present in new conf
        obsoleteHostPorts ++= prevJmxObjects.groupBy(_.hostPort).keySet -- jmxObjects.groupBy(_.hostPort).keySet
      }
      jmxObjectIndexes.synchronized {
        jmxObjectIndexes = newIndexes
      }
      val confTimeout = topLevelConfMap.getOrElse("rmi_socket_timeout", SMGJmxConnection.DEFAULT_RMI_SOCKET_TIMEOUT_MS).asInstanceOf[Int]
      if (confTimeout != rmiSocketTimeoutMillis) { // only do this on change
        log.info(s"SMGJmxPlugin.reloadConf: updating RMI timeout to $confTimeout")
        SMGJmxConnection.setRMITimeout(confTimeout)
        rmiSocketTimeoutMillis = confTimeout
      }
      log.info("SMGJmxPlugin.reloadConf: confMap=" + topLevelConfMap + " jmxObjects.size=" + jmxObjects.size)
    } catch {
      case t: Throwable => log.ex(t, "SMGJmxPlugin.reloadConf: Unexpected exception: " + t)
    }
  }

  /**
    * This is called at the end of the run
    */
  private def cleanup(): Unit = {
    // "best effort"
    try {
      obsoleteHostPorts.toList.foreach { hostPort =>
        jmxClient.removeJmxConnection(hostPort)
        obsoleteHostPorts.remove(hostPort)
      }
    } catch {
      case t: Throwable => {
        log.ex(t, "SMGJmxPlugin.reloadConf: Unexpected exception during cleanup (ignoring)")
      }
    }
  }

  private def onRunComplete(): Unit = {
    cleanup()
    finished()
    log.info("SMGJmxPlugin.onRunComplete: finished")
  }

  val myUpdateEc: ExecutionContext = smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.jmx-plugin")

  private val updateActor =
    smgConfSvc.actorSystem.actorOf(Props(
      new SMGJmxUpdateActor(smgConfSvc, this, confParser, jmxClient, runCounterName, onRunComplete _)
    ))


  private val MAX_OVERLAP_INTERVALS = 5
  private var lastRunTss = SMGRrd.tssNow

  override def run(): Unit = {
    Future {
      try {
        if (!checkAndSetRunning) {
          if (SMGRrd.tssNow - lastRunTss >= (MAX_OVERLAP_INTERVALS * interval)){
            log.error(s"SMGJmxPlugin.run: too many overlapping runs - resetting state for next run")
            finished()
          } else {
            log.error(s"SMGJmxPlugin.run: overlapping runs - aborting")
          }
          smgConfSvc.sendRunMsg(SMGDataFeedMsgRun(interval, List(s"JMX ($pluginId) - overlapping runs"), Some(pluginId)))
        } else {
          lastRunTss = SMGRrd.tssNow
          smgConfSvc.sendRunMsg(SMGDataFeedMsgRun(interval, List(), Some(pluginId)))
          val objectsToUpdate = jmxObjects.synchronized(jmxObjects)
          if (objectsToUpdate.isEmpty){
            log.info(s"SMGJmxPlugin.run: no objects defined")
            onRunComplete()
          } else {
            val sz = objectsToUpdate.size
            SMGRunStats.resetCustomCounter(runCounterName, sz)
            val byHostPort = objectsToUpdate.groupBy(_.hostPort)
            byHostPort.foreach { t =>
              val hostPort = t._1
              val objs = t._2
              updateActor ! SMGJmxUpdateActor.SMGJmxUpdateMessage(hostPort, objs)
              log.debug(s"SMGJmxPlugin.run: sent update message for: $hostPort (${objs.size} objects)")
            }
            log.info(s"SMGJmxPlugin.run: sent messages for ${byHostPort.keys.size} host/ports ($sz JMX objects)")
          }
        }
      } catch {
        case t: Throwable => {
          log.ex(t, "SMGJmxPlugin.run: Unexpected exception: " + t)
          finished()
        }
      }
    }(myUpdateEc)

  }

  override def indexes: Seq[SMGConfIndex] = {
    jmxObjectIndexes
  }

  override def htmlContent(httpParams: Map[String,String]): String = {
    <h3>Plugin {pluginId}: debug information - please use the
      <a href="/dash?ix=jmx_all">dashboard</a> view to browse the graphs</h3>
      <ul>
        <li>Num objects: {jmxObjects.size}</li>
        <li>Num indexes: {jmxObjectIndexes.size}</li>
      </ul>
    <h4>Objects by host:port</h4>
    <ul>
      {
        jmxObjects.groupBy(_.hostPort).map { case (hostPort, objs) =>
          <li>
            <h5>{hostPort}</h5>
            <ul>
              {
                objs.map { obj =>
                  <li>{obj}</li>
                }
              }
            </ul>
          </li>
        }
      }
    </ul>

  }.mkString




}

