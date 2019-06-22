package com.smule.smgplugins.livemon

import com.smule.smg._
import com.smule.smg.config.{SMGConfIndex, SMGConfigService}
import com.smule.smg.core.SMGObjectView
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import play.libs.Akka

import scala.concurrent.ExecutionContext

/**
  * Created by asen on 7/5/16.
  * This is a skeleton for an example plugin which can process SMG monitoring data
  */
class SMGLiveMonPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                       ) extends SMGPlugin  {

  private val myEc: ExecutionContext = smgConfSvc.actorSystem.dispatchers.lookup("akka-contexts.monitor-plugin")

  private val log = new SMGPluginLogger(pluginId)

  override def objects: Seq[SMGObjectView] = List()
  override def indexes: Seq[SMGConfIndex] = List()


  private val dataReceiver = new SMGLiveMonDataListener(log)

  smgConfSvc.registerDataFeedListener(dataReceiver)

  override def reloadConf(): Unit = {

  }

  override def run(): Unit = {

  }
}
