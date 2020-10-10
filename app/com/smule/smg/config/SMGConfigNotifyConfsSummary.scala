package com.smule.smg.config

import com.smule.smg.{config, monitor}
import com.smule.smg.config.SMGConfigNotifyConfsSummary.{CommandsNotifyIndexConfSummary, CommandsNotifyObjectConfSummary, VarsNotifySeverityConfSummary}
import com.smule.smg.core.{SMGFetchCommand, SMGObjectUpdate}
import com.smule.smg.monitor.{SMGMonAlertConfSource, SMGMonNotifyCmd, SMGState}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class SMGConfigNotifyConfsSummary(
                                     remoteId: Option[String],
                                     allNotifyCmds: Seq[SMGMonNotifyCmd],
                                     cmdsDefault: CommandsNotifyObjectConfSummary,
                                     cmdsIdxSeq: Seq[CommandsNotifyIndexConfSummary],
                                     cmdsObjSeq: Seq[CommandsNotifyObjectConfSummary],
                                     varsBySeverity: Map[SMGState.Value, VarsNotifySeverityConfSummary],
                                     errMsg: Option[String]
                                   ) {
  val varsBySeverityOrdered: Seq[(monitor.SMGState.Value, VarsNotifySeverityConfSummary)] =
    varsBySeverity.toSeq.sortBy(_._1)(Ordering[SMGState.Value].reverse)
}

object SMGConfigNotifyConfsSummary {

  trait NotifyDef {
    val notifyCmds: Seq[String]
    val notifyBackoff: Option[Int]
    val notifyDisable: Boolean
    val notifyStrikes: Option[Int]
  }

  case class NotifyDefObj(
                           src: SMGMonAlertConfSource.Value,
                           srcId: Option[String],
                           varDesc: String,
                           atSeverity: SMGState.Value,
                           notifyCmds: Seq[String],
                           notifyBackoff: Option[Int],
                           notifyDisable: Boolean,
                           notifyStrikes: Option[Int]
                         ) extends NotifyDef {


  }

  case class CommandsNotifyIndexConfSummary(
                                             isHidden: Boolean,
                                             srcId: String,
                                             notifyCmds: Seq[String],
                                             notifyBackoff: Option[Int],
                                             notifyDisable: Boolean,
                                             notifyStrikes: Option[Int],
                                             numCmds: Int,
                                             sampleCmdIds: Seq[String]
                                           ) extends NotifyDef

  case class CommandsNotifyObjectConfSummary(
                                              objsDesc: Option[String],
                                              notifyCmds: Seq[String],
                                              notifyBackoff: Option[Int],
                                              notifyDisable: Boolean,
                                              notifyStrikes: Option[Int],
                                              numCmds: Int,
                                              sampleCmdIds: Seq[String]
                                            ) extends NotifyDef

  case class VarsNotifyIndexConfSummary(
                                         isHidden: Boolean,
                                         srcId: String,
                                         atSeverity: SMGState.Value,
                                         varDesc: String,
                                         notifyCmds: Seq[String],
                                         notifyBackoff: Option[Int],
                                         notifyDisable: Boolean,
                                         notifyStrikes: Option[Int],
                                         numVars: Int,
                                         numObjs: Int,
                                         sampleOids: Seq[String]
                                       ) extends NotifyDef

  case class VarsNotifyObjectConfSummary(
                                        atSeverity: SMGState.Value,
                                        varDesc: String,
                                        notifyCmds: Seq[String],
                                        notifyBackoff: Option[Int],
                                        notifyDisable: Boolean,
                                        notifyStrikes: Option[Int],
                                        numVars: Int,
                                        numObjs: Int,
                                        sampleOids: Seq[String]
                                        ) extends NotifyDef

  case class VarsNotifySeverityConfSummary(
                                            atSeverity: SMGState.Value,
                                            varsDefault: VarsNotifyObjectConfSummary,
                                            varsIdxSeq: Seq[VarsNotifyIndexConfSummary],
                                            varsObjsSeq: Seq[VarsNotifyObjectConfSummary]
                                          )





  private def getDefaultVarNotifyConfForSeverity(conf: SMGLocalConfig, sev: SMGState.Value): VarsNotifyObjectConfSummary = {
    val ncmds = sev match {
      case SMGState.ANOMALY => conf.globalAnomNotifyConf
      case SMGState.WARNING => conf.globalWarnNotifyConf
      case SMGState.FAILED => conf.globalUnknNotifyConf
      case SMGState.CRITICAL => conf.globalCritNotifyConf
    }
    VarsNotifyObjectConfSummary(
      atSeverity = sev,
      varDesc = "*",
      notifyCmds = ncmds.map(_.id),
      notifyBackoff = Some(conf.globalNotifyBackoff),
      notifyDisable = false,
      notifyStrikes = Some(conf.globalNotifyStrikes),
      numVars = 0,
      numObjs = 0,
      sampleOids = Seq()
    )
  }

  private def notifyCommandsFetchErrorsSummary(conf: SMGLocalConfig) = {
    // group all fetch cmd FAILED state recipients
    val indexDefinedCmds = ListBuffer[SMGFetchCommand]()
    val objectDefinedCmds = ListBuffer[SMGFetchCommand]()
    val defaultCmds = ListBuffer[SMGFetchCommand]()
    conf.allCommandsById.values.foreach { cmd =>
      if (cmd.notifyConf.isEmpty)
        defaultCmds += cmd
      else if (cmd.notifyConf.get.src == SMGMonAlertConfSource.OBJ)
        objectDefinedCmds += cmd
      else
        indexDefinedCmds += cmd
    }

    val indexCmds = indexDefinedCmds.groupBy(_.notifyConf.get).map { case (nc, cmdSeq) =>
      CommandsNotifyIndexConfSummary(
        isHidden = nc.src == SMGMonAlertConfSource.HINDEX,
        srcId = nc.srcId,
        notifyCmds = nc.fail,
        notifyBackoff = nc.notifyBackoff,
        notifyDisable = nc.notifyDisable,
        notifyStrikes = nc.notifyStrikes,
        numCmds = cmdSeq.size,
        sampleCmdIds = cmdSeq.take(10).map(_.id)
      )
    }.toSeq.sortBy(_.srcId)

    val objsCmds = objectDefinedCmds.groupBy { cmd =>
      cmd.notifyConf.map{ x =>
        (x.fail, x.notifyBackoff, x.notifyDisable, x.notifyStrikes)
      }.get
    }.map { case (gbKey, cmdSeq) =>
      val cmdIds = cmdSeq.map(_.id)
      CommandsNotifyObjectConfSummary(
        objsDesc = if(cmdIds.lengthCompare(1) > 0)
          Some(SMGStringUtils.commonPxSxAddDesc(cmdIds))
        else None,
        notifyCmds = gbKey._1,
        notifyBackoff = gbKey._2,
        notifyDisable = gbKey._3,
        notifyStrikes = gbKey._4,
        numCmds = cmdSeq.size,
        sampleCmdIds = cmdIds.take(10)
      )
    }.toSeq

    val defaultCmd = CommandsNotifyObjectConfSummary(
      objsDesc = None,
      notifyCmds = conf.globalUnknNotifyConf.map(_.id),
      notifyBackoff = Some(conf.globalNotifyBackoff),
      notifyDisable = false,
      notifyStrikes = Some(conf.globalNotifyStrikes),
      numCmds = defaultCmds.size,
      sampleCmdIds = defaultCmds.take(10).map(_.id)
    )

    (defaultCmd, indexCmds, objsCmds)
  }

  private def notifyCommandsVarStateSummary(conf: SMGLocalConfig) = {
    val allNonDefaultNotifs = ListBuffer[(SMGObjectUpdate, Int, NotifyDefObj)]()

    conf.updateObjects.foreach { ou =>
      val ncObj = conf.objectNotifyConfs.get(ou.id)
      ou.vars.indices.foreach { vix =>
        if (ncObj.isDefined){
          val confsForVar = ncObj.get.varConfs.get(vix)
          if (confsForVar.isDefined){
            val vmap = ou.vars.lift(vix).getOrElse(Map[String,String]())
            val varDesc = Seq(
              vmap.get("label").map(x => ("label", x)),
              vmap.get("mu").map(x => ("mu", x)),
              Some(("vix", vix))
            ).flatten.map {t => s"${t._1}=${t._2}"}.mkString(" ")
            confsForVar.get.foreach { nc =>
              val toAdd = ListBuffer[NotifyDefObj]()

              def notifyDefObj(sev: SMGState.Value, cmdIds: Seq[String]) = NotifyDefObj(
                src = nc.src,
                srcId = if (nc.src == SMGMonAlertConfSource.OBJ) None else Some(nc.srcId) ,
                varDesc = varDesc,
                atSeverity = sev,
                notifyCmds = cmdIds.sorted,
                notifyBackoff = nc.notifyBackoff,
                notifyDisable = nc.notifyDisable,
                notifyStrikes = nc.notifyStrikes
              )

              if (nc.notifyDisable){
                Seq(SMGState.ANOMALY, SMGState.WARNING, SMGState.CRITICAL).foreach { sval =>
                  toAdd += notifyDefObj(sval, Seq())
                }
              } else {
                if (nc.anom.nonEmpty){
                  toAdd += notifyDefObj(SMGState.ANOMALY, nc.anom)
                }
                if (nc.warn.nonEmpty){
                  toAdd += notifyDefObj(SMGState.WARNING, nc.warn)
                }
                if (nc.crit.nonEmpty){
                  toAdd += notifyDefObj(SMGState.CRITICAL, nc.crit)
                }
              }
              toAdd.foreach { ndo =>
                allNonDefaultNotifs += Tuple3(ou, vix, ndo)
              }
            }
          }
        }
      }
    } // updateObjects.foreach
    val varsBySeverity = mutable.Map[SMGState.Value, VarsNotifySeverityConfSummary]()
    allNonDefaultNotifs.groupBy(_._3.atSeverity).foreach { case (sev, t3Seq) =>
      val varsIdxSeq = ListBuffer[VarsNotifyIndexConfSummary]()
      val varsObjsSeq = ListBuffer[VarsNotifyObjectConfSummary]()
      t3Seq.groupBy(_._3).foreach { case (nd, t3SubSeq) =>
        val t3Objs = t3SubSeq.map(_._1.id).distinct
        if (nd.srcId.isDefined){
          varsIdxSeq += VarsNotifyIndexConfSummary(
            isHidden = nd.src == SMGMonAlertConfSource.HINDEX,
            srcId = nd.srcId.get,
            atSeverity = nd.atSeverity,
            varDesc = nd.varDesc,
            notifyCmds = nd.notifyCmds,
            notifyBackoff = nd.notifyBackoff,
            notifyDisable = nd.notifyDisable,
            notifyStrikes = nd.notifyStrikes,
            numVars = t3SubSeq.size,
            numObjs = t3Objs.size,
            sampleOids = t3Objs.take(10)
          )
        } else {
          val addDesc = if (t3Objs.lengthCompare(1) > 0)
            SMGStringUtils.commonPxSxAddDesc(t3Objs) + ", "
          else
            "Single object, "
          varsObjsSeq += VarsNotifyObjectConfSummary(
            atSeverity = nd.atSeverity,
            varDesc =  addDesc + nd.varDesc,
            notifyCmds = nd.notifyCmds,
            notifyBackoff = nd.notifyBackoff,
            notifyDisable = nd.notifyDisable,
            notifyStrikes = nd.notifyStrikes,
            numVars = t3SubSeq.size,
            numObjs = t3Objs.size,
            sampleOids = t3Objs.take(10)
          )
        }
      }
      varsBySeverity.put(sev,
        VarsNotifySeverityConfSummary(
          atSeverity = sev,
          varsDefault = getDefaultVarNotifyConfForSeverity(conf, sev),
          varsIdxSeq = varsIdxSeq.toList,
          varsObjsSeq = varsObjsSeq.toList
        )
      )
    }
    varsBySeverity.toMap
  }


  def build(conf: SMGLocalConfig): SMGConfigNotifyConfsSummary = {
    val fcData = notifyCommandsFetchErrorsSummary(conf)
    val varData = notifyCommandsVarStateSummary(conf)

    config.SMGConfigNotifyConfsSummary(
      remoteId = None,
      allNotifyCmds = conf.notifyCommands.values.toSeq.sortBy(_.id),
      cmdsDefault = fcData._1,
      cmdsIdxSeq = fcData._2,
      cmdsObjSeq = fcData._3,
      varsBySeverity = varData,
      errMsg = None
    )
  }
}
