package helpers

import akka.actor.ActorSystem
import com.smule.smg._

/**
  * Created by asen on 9/5/16.
  */
class TestConfigSvc() extends SMGConfigService {

  def cleanTestOut: Unit = {
    SMGCmd("rm -f test-out/*").run
  }

  def rrdObject(oid: String, numVars: Int, pfId: Option[String]) = SMGRrdObject(id = oid,
    command = SMGCmd((1 to numVars).map(i => s"echo $i").mkString(" && ")),
    vars =  (1 to numVars).map(i => Map("label" -> s"var$i")).toList,
    title = s"$oid Title",
    rrdType = "GAUGE",
    interval = 60,
    dataDelay = 0,
    stack = false,
    preFetch = pfId,
    rrdFile = Some(s"test-out/$oid.rrd"),
    rraDef = None,
    None,
    None
  )

  def rrdAggObject(oid: String, ous: Seq[SMGObjectUpdate], numVars: Int): SMGRrdAggObject = {
    SMGRrdAggObject(id = oid,
      ous = ous,
      aggOp = "SUM",
      vars =  (1 to numVars).map(i => Map("label" -> s"var$i", "cdef" -> "$ds,2,*")).toList,
      title = "test,object.1 Title",
      rrdType = "GAUGE",
      interval = 60,
      dataDelay = 0,
      stack = false,
      rrdFile = Some(s"test-out/$oid.rrd"),
      rraDef = None,
      None,
      None
    )
  }

  override def config: SMGLocalConfig = {
    val aggou1 = rrdObject("test.object.aggou1", 2, None)
    val aggou2 = rrdObject("test.object.aggou2", 2, None)
    val aggu = rrdAggObject("test.object.aggu", Seq(aggou1,aggou2), 2)
    SMGLocalConfig(
      globals = Map(
        "$monlog_dir" -> "test-out",
        "$monstate_dir" -> "test-out",
        "$notify-spike" -> "test-notify",
        "$notify-warn" -> "test-notify",
        "$notify-unkn" -> "test-notify",
        "$notify-crit" -> "test-notify",
        "$notify-global" -> "test-notify"
      ),
      confViewObjects = Seq(
        rrdObject("test.object.1", 2, None),
        rrdObject("test.pf.object.1", 2, Some("test.prefetch")),
        rrdObject("test.pf.object.2", 2, Some("test.prefetch")),
        rrdObject("test.pf.object.3", 2, Some("test.prefetch")),
        aggou1,
        aggou2,
        aggu
      ),
      indexes = Seq(
        SMGConfIndex(id = "test.index.1",
          title = "Test Index 1",
          flt = SMGFilter.fromPrefixLocal("test."),
          cols = None,
          rows = None,
          aggOp = None,
          xRemoteAgg = false,
          aggGroupBy = None,
          period = None,
          desc = None,
          parentId = None,
          childIds= Seq[String](),
          disableHeatmap = false)
      ),
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
            0 -> Seq(
              SMGMonVarAlertConf(
                SMGMonAlertConfSource.OBJ,
                "test.object.1",
                crit = Some(SMGMonAlertThresh(5.0, "gte")),
                warn = Some(SMGMonAlertThresh(3.0, "gte")),
                pluginChecks = Seq()
              ),
              SMGMonVarAlertConf(
                SMGMonAlertConfSource.OBJ,
                "test.object.1",
                crit = Some(SMGMonAlertThresh(0.0, "eq")),
                warn = None,
                pluginChecks = Seq()
              )
            )
          )
        ),
        "test.pf.object.1" -> SMGMonObjAlertConf(
          varConfs = Map(
            0 -> Seq(SMGMonVarAlertConf(
              SMGMonAlertConfSource.OBJ,
              "test.pf.object.1",
              crit = Some(SMGMonAlertThresh(5.0, "gte")),
              warn = Some(SMGMonAlertThresh(3.0, "gte")),
              pluginChecks = Seq()
            ))
          )
        )
      ),
      notifyCommands = Map("test-notify" ->
        SMGMonNotifyCmd("test-notify", "env >test-out/test.out ; echo >> test-out/test.out", 30)),
      objectNotifyConfs = Map(),
      hiddenIndexes = Map(),
      configErrors = List()
    )
  }

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

  override val executionContexts: ExecutionContexts = new TestExecutionContexts()

  /**
    * Store recently fetched object value into cache.
    *
    * @param ou   - object update
    * @param tss  - fetch timestamp (seconds)
    * @param vals - fetched values
    */
  override def cacheValues(ou: SMGObjectUpdate, tss: Int, vals: List[Double]): Unit = {}

  /**
    * Get the latest cached values for given object
    *
    * @param ou - object update
    * @return
    */
  override def getCachedValues(ou: SMGObjectUpdate): List[Double] = List()

  /**
    * Invalidate any previously cached values for this object
    *
    * @param ou
    */
  override def invalidateCachedValues(ou: SMGObjectUpdate): Unit = {}

  override val smgVersionStr: String = "test"
  override val defaultInterval: Int = 60
  override val defaultTimeout: Int = 30
}
