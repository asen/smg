package com.smule.smg

/**
  * Created by asen on 11/3/16.
  */

/**
  * SMG builds a tree of commands to be run for object updates to happen
  * Each object can specify a pre_fetch command which will be run before this
  * object fetch. Each pre_fetch itself can specify parent command too.
  * This trait encapsulates the common properties of a pre_fetch command and
  * a rrd object command and its main purpose is to simplify building the commands
  * tree
  */
trait SMGFetchCommand {
  val id: String
  val command: SMGCmd
  val preFetch: Option[String]
  val isRrdObj: Boolean
  val ignoreTs = false // TODO
}


/**
  * A (de)serializable version of SMGFetchCommand, used to keep a local copy
  * of a remote runtree
  */
case class SMGFetchCommandView(id: String,
                               command: SMGCmd,
                               preFetch: Option[String],
                               isRrdObj: Boolean) extends SMGFetchCommand

/**
  * A class representing a tree of fetch commands to be run where children will be run only
  * if parent commands succeed.
  * @param node - the root node of the tree
  * @param children - the first-level children of that tree (each is a tree itself)
  */
case class SMGFetchCommandTree(node: SMGFetchCommand, children: Seq[SMGFetchCommandTree]) {
  val isLeaf = children.isEmpty

  def size: Int = if (isLeaf) 1 else children.map(_.size).sum + 1

  def leafNodes: Seq[SMGFetchCommand] = if (isLeaf) List(node) else children.flatMap(_.leafNodes)

  def findTree(cmdId: String): Option[SMGFetchCommandTree] = {
    if (cmdId == node.id)
      Some(this)
    else if (children.isEmpty)
      None
    else {
      SMGFetchCommandTree.findTreeWithRoot(cmdId, children)
    }
  }
}

object SMGFetchCommandTree {
  def findTreeWithRoot(rootId: String, where: Seq[SMGFetchCommandTree]): Option[SMGFetchCommandTree] = {
    var found: Option[SMGFetchCommandTree] = None
    if (where.nonEmpty) {
      where.find { ct =>
        found = ct.findTree(rootId)
        found.isDefined // break if found
      }
    }
    found
  }
}
