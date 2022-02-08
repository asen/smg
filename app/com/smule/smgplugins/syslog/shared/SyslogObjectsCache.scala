package com.smule.smgplugins.syslog.shared

import com.smule.smg.core.{SMGCmd, SMGLoggerApi, SMGObjectUpdate, SMGObjectView}
import com.smule.smgplugins.syslog.config.SMGObjectTemplate

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap


class SyslogObjectsCache(serverId: String, objectTemplate: SMGObjectTemplate, maxCapacity: Int, log: SMGLoggerApi) {

  private val cache = TrieMap[String, SMGObjectUpdate]()
  private val dirty: AtomicBoolean = new AtomicBoolean(false)

  def getOrCreateObject(ld: LineData): SMGObjectUpdate = {
    val pid = objectTemplate.getPrefixedId(ld)
    cache.getOrElseUpdate(pid, {
      dirty.set(true)
      objectTemplate.getRrdObjectfromLineData(pid, ld)
    })
  }

  def checkCapacity(): Unit = {
    if (cache.size >= maxCapacity) {
      log.error(s"SyslogObjectsCache($serverId).checkCapacity: Cache size exceeded capacity " +
        s"(${cache.size} >= ${maxCapacity}) - purging")
      cache.clear()
    }
  }

  def isDirty: Boolean = dirty.get()

  def getObjectViews: Seq[SMGObjectView] = {
    dirty.set(false)
    cache.values.toSeq.sortBy(_.id)
  }

  def loadObjectsFromDisk(): Unit = {
    val myBaseDir = objectTemplate.rrdBaseDir.stripSuffix(File.separator)
    log.info(s"SyslogObjectsCache($serverId).loadObjectsFromDisk: loading objects from ${myBaseDir} ...")
    // TODO rewrite this in Scala/Java?
    val findOutput =
      SMGCmd(s"find ${myBaseDir} -type f -name '*.rrd'").run()
    findOutput.foreach { rrdfname =>
      val objectId = new File(rrdfname).getName.stripSuffix(".rrd")
      cache.put(objectId, objectTemplate.getRrdObjectFromIdAndFile(objectId, rrdfname))
    }
    log.info(s"SyslogObjectsCache($serverId).loadObjectsFromDisk: loaded ${cache.size} objects from ${myBaseDir}.")
  }
}
