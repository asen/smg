package com.smule.smg.core

/**
  * SMG builds a tree of commands to be run for object updates to happen
  * Each object can specify a pre_fetch command which will be run before this
  * object fetch. Each pre_fetch itself can specify parent command too.
  * This trait encapsulates the common properties of a pre_fetch command and
  * a rrd object command and its main purpose is to simplify building the commands
  * tree
  */
trait SMGFetchCommand extends SMGTreeNode {
  //  val id: String // in tree node
  val command: SMGCmd
  val preFetch: Option[String]
  val isRrdObj: Boolean

  override def parentId: Option[String] = preFetch

  // these are only meaningful in local/non-rrd obj context, which overrides them
  val ignoreTs = false
  val childConc: Int = 1
  val passData: Boolean = false
}
