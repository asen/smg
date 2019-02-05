package com.smule.smg.grapher

import java.io.File

import akka.actor.{Actor, Props}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.core.{SMGLogger, SMGObjectView}
import com.smule.smg.rrd.{SMGRrdConfig, SMGRrdGraph, SMGRrdGraphAgg}

import scala.concurrent.Future

/**
  * Created by asen on 11/10/15.
  */

/**
  * Actor responsible for processing SMG graph messages.
  */
class SMGraphActor extends Actor {
  import SMGraphActor._

  val log = SMGLogger

  val CACHE_FOR = 10000 // TODO 10 seconds, make more(?) and configurable

  override def receive: Receive = {

    case SMGraphMessage(configSvc: SMGConfigService,
                        obj:SMGObjectView, period:String,
                        gopts: GraphOptions, outFn:String) => {
      val rrdConf: SMGRrdConfig = configSvc.config.rrdConf
      val rrdGraphCtx = configSvc.executionContexts.rrdGraphCtx
      val mySender = sender()
      Future {
        try {
          val rrd = if (obj.isAgg) {
            new SMGRrdGraphAgg(rrdConf, obj.asInstanceOf[SMGAggObjectView])
          } else {
            new SMGRrdGraph(rrdConf, obj)
          }
          val outF = new File(outFn)
          var cached = true
          if (!outF.exists || (System.currentTimeMillis() - outF.lastModified() > CACHE_FOR)) {
            rrd.graph(outFn, period, gopts)
            cached = false
          }
          mySender ! SMGraphReadyMessage(obj.id, period, cached, error = false)
        } catch {
          case (e: Throwable) => {
            log.ex(e, "SMGraphActor: SMGraphMessage caused unexpected error")
            mySender ! SMGraphReadyMessage(obj.id, period, cached = false, error = true)
          }
        }
        //log.info("Message sent back")
      } (rrdGraphCtx)
    }
  }
}

object SMGraphActor {
  def props = Props[SMGraphActor]
  case class SMGraphMessage(configSvc: SMGConfigService, obj:SMGObjectView, period:String, gopts: GraphOptions, outFn:String)
  case class SMGraphReadyMessage(id: String, period: String, cached: Boolean, error: Boolean)
}
