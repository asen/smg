package com.smule.smgplugins.livemon

import com.smule.smg._
import play.libs.Akka

import scala.concurrent.ExecutionContext

/**
  * Created by asen on 7/5/16.
  */
class SMGLiveMonPlugin(val pluginId: String,
                        val interval: Int,
                        val pluginConfFile: String,
                        val smgConfSvc: SMGConfigService
                       ) extends SMGPlugin  {

  private val myEc: ExecutionContext = Akka.system.dispatchers.lookup("akka-contexts.monitor-plugin")

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
