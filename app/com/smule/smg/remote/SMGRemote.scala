package com.smule.smg.remote

import scala.collection.mutable

/**
  * Created by asen on 11/19/15.
  */

/**
  * Representation of a remote configuration
  * @param id - unique remote id
  * @param url - HTTP URL where the remote is accessible
  */
case class SMGRemote(id: String, url:String, slaveId: Option[String] = None,
                     graphTimeoutMs: Option[Long] = None,
                     configFetchTimeoutMs: Option[Long] = None
                    ) {
  lazy val name: String = if (id == SMGRemote.local.id) SMGRemote.localName else id
}

/**
  * Singleton defining helpers for dealing with remotes and their ids.
  */
object SMGRemote {
  val local = SMGRemote("^", "") // this is special in many ways ...

  val localName = "Local"

  val wildcard = SMGRemote("*", "")

  def isRemoteObj(id: String): Boolean = id.startsWith("@")

  def isLocalObj(id: String): Boolean = !isRemoteObj(id)

  /**
    * get the id of the remote this object belongs to (@<remote-id>....
    * @param id
    * @return
    */
  def remoteId(id: String): String = if (isRemoteObj(id)) { id.substring(1).split("\\.", 2)(0) } else SMGRemote.local.id

  def localId(id: String): String = if (isRemoteObj(id)) {
    val dotix = id.indexOf('.')
    if (dotix >= 0) {
      id.substring(dotix + 1)
    } else ""
  } else id

  def prefixedId(rid: String, id: String): String = if (rid == SMGRemote.local.id) id else "@" + rid + "." + id

  def fromYamlMap(yamlMap: mutable.Map[String, Object]): Option[SMGRemote] = {
    if (yamlMap.contains("id") && yamlMap.contains("url"))
      None
    else Some(
      SMGRemote(
        id = yamlMap("id").toString,
        url = yamlMap("url").toString,
        slaveId = yamlMap.get("slave_id").map(_.toString),
        graphTimeoutMs = yamlMap.get("graph_timeout_ms").map(_.toString.toLong), //XXX ugly ...
        configFetchTimeoutMs = yamlMap.get("config_fetch_timeout_ms").map(_.toString.toLong) //XXX ugly ...
      )
    )
  }

}
