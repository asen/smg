package com.smule.smg.core

/**
  * A class representing a tree of fetch commands to be run where children will be run only
  * if parent commands succeed.
 *
  * @param node - the root node of the tree
  * @param children - the first-level children of that tree (each is a tree itself)
  */
//TODO refactor this to use SMGTree[SMGFetchCommand]
case class SMGFetchCommandTree(node: SMGFetchCommand, children: Seq[SMGFetchCommandTree]) {
  val isLeaf = children.isEmpty

  def size: Int = if (isLeaf) 1 else children.map(_.size).sum + 1

  def leafNodes: Seq[SMGFetchCommand] = if (isLeaf) Seq(node) else children.flatMap(_.leafNodes)

  def allNodes:  Seq[SMGFetchCommand] = Seq(node) ++ children.flatMap(_.allNodes)

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