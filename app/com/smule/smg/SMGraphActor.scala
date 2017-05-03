package com.smule.smg

import java.io.File

import akka.actor.{Actor, Props}

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
    case SMGraphMessage(rrdConf:SMGRrdConfig, obj:SMGObjectView, period:String, gopts: GraphOptions, outFn:String) => {
      val mySender = sender()
      Future {
        try {
          val rrd = new SMGRrdGraph(rrdConf, obj)
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
      } (ExecutionContexts.rrdGraphCtx)
    }

    case SMGraphAggMessage(rrdConf:SMGRrdConfig, obj:SMGAggObjectView, period:String, gopts: GraphOptions, outFn:String) => {
      //log.info("--- SMGraphActor ---")
      val mySender = sender() // sender() is not available in our rrdGraphCtx future so use a reference stored here
      Future {
        try {
          val rrd = new SMGRrdGraphAgg(rrdConf, obj)
          val outF = new File(outFn)
          var cached = true
          if (!outF.exists || (System.currentTimeMillis() - outF.lastModified() > CACHE_FOR)) {
            rrd.graph(outFn, period, gopts)
            cached = false
          }
          mySender ! SMGraphReadyMessage(obj.id, period, cached, error = false)
        } catch {
          case (e: Throwable) => {
            log.ex(e, "SMGraphActor: SMGraphAggMessage caused unexpected error")
            mySender ! SMGraphReadyMessage(obj.id, period, cached = false, error = true)
          }
        }
        //log.info("Message sent back")
      } (ExecutionContexts.rrdGraphCtx)
    }

  }
}

object SMGraphActor {
  def props = Props[SMGraphActor]
  case class SMGraphMessage(rrdConf:SMGRrdConfig, obj:SMGObjectView, period:String, gopts: GraphOptions, outFn:String)
  case class SMGraphAggMessage(rrdConf:SMGRrdConfig, obj: SMGAggObjectView, period:String, gopts: GraphOptions, outFn:String)
  case class SMGraphReadyMessage(id: String, period: String, cached: Boolean, error: Boolean)
}
