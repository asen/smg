package helpers

import akka.actor.ActorSystem
import com.smule.smg._

/**
  * Created by asen on 9/5/16.
  */
class TestConfigSvc() extends SMGConfigService {

  def cleanTestOut = {
    SMGCmd("rm -f test-out/*").run
  }

  def rrdObject(oid: String, numVars: Int, pfId: Option[String]) = SMGRrdObject(id = oid,
    command = SMGCmd((1 to numVars).map(i => s"echo $i").mkString(" && ")),
    vars =  (1 to numVars).map(i => Map("label" -> s"var$i")).toList,
    title = "test,object.1 Title",
    rrdType = "GAUGE",
    interval = 60,
    stack = false,
    preFetch = pfId,
    rrdFile = Some(s"test-out/$oid.rrd"),
    rraDef = None,
    None,
    None
  )

  override def config: SMGLocalConfig = SMGLocalConfig(
    globals = Map(
      "$monlog_dir" -> "test-out",
      "$monstate_dir" -> "test-out",
      "$notify-spike" -> "test-notify",
      "$notify-warn" -> "test-notify",
      "$notify-crit" -> "test-notify",
      "$notify-global" -> "test-notify"
    ),
    confViewObjects = Seq(
      rrdObject("test.object.1", 2, None),
      rrdObject("test.pf.object.1", 2, Some("test.prefetch")),
      rrdObject("test.pf.object.2", 2, Some("test.prefetch")),
      rrdObject("test.pf.object.3", 2, Some("test.prefetch"))
    ),
    indexes = Seq(),
    rrdConf = SMGRrdConfig("rrdtool", None, 607, 400, None),
    imgDir = "test-out",
    urlPrefix = "",
    intervals = Set(60),
    preFetches = Map("test.prefetch" -> SMGPreFetchCmd("test.prefetch", SMGCmd("echo 0"), None, ignoreTs = false, 1, None)),
    remotes = Seq(),
    remoteMasters = Seq(),
    pluginObjects = Map(),
    pluginPreFetches = Map(),
    objectAlertConfs = Map(
      "test.object.1" -> SMGMonObjAlertConf(
        varConfs = Map(
          0 -> Seq(SMGMonVarAlertConf(
            SMGMonAlertConfSource.OBJ,
            "test.object.1",
            crit = Some(SMGMonAlertThresh(5.0, "gte")),
            warn = Some(SMGMonAlertThresh(3.0, "gte")),
            spike = Some(SMGMonSpikeThresh(""))
          ))
        )
      ),
      "test.pf.object.1" -> SMGMonObjAlertConf(
        varConfs = Map(
          0 -> Seq(SMGMonVarAlertConf(
            SMGMonAlertConfSource.OBJ,
            "test.pf.object.1",
            crit = Some(SMGMonAlertThresh(5.0, "gte")),
            warn = Some(SMGMonAlertThresh(3.0, "gte")),
            spike = Some(SMGMonSpikeThresh(""))
          ))
        )
      )
    ),
    notifyCommands = Map("test-notify" -> SMGMonNotifyCmd("test-notify", "echo", 30)),
    objectNotifyConfs = Map(),
    hiddenIndexes = Map(),
    configErrors = List()
  )

  /**
    * reload config.yml
    */
  override def reload(): Unit = {}

  override val useInternalScheduler: Boolean = true
  override val plugins: Seq[SMGPlugin] = Seq()
  override val pluginsById: Map[String, SMGPlugin] = Map()

  override def registerDataFeedListener(lsnr: SMGDataFeedListener): Unit = {}

  override protected def dataFeedListeners: Seq[SMGDataFeedListener] = Seq()

  override def registerReloadListener(lsnr: SMGConfigReloadListener): Unit = {}

  override def notifyReloadListeners(ctx: String): Unit = {}

  override val actorSystem: ActorSystem = null
}
