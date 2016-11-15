package com.smule.smg

import scala.collection.mutable.ListBuffer

/**
  * Created by asen on 11/12/16.
  */

// Intrerface for representing a tree node
trait SMGTreeNode {
  val id: String
  def parentId: Option[String]
}

// a tree object which can hold SMGTreeNode descendants
case class SMGTree[T <: SMGTreeNode](node: T, children: Seq[SMGTree[T]]) {

  val isLeaf = children.isEmpty

  def size: Int = if (isLeaf) 1 else children.map(_.size).sum + 1

  def leafNodes: Seq[T] = if (isLeaf) List(node) else children.flatMap(_.leafNodes)

  def allNodes: Seq[T] = Seq(node) ++ children.flatMap(_.allNodes)

  def findTree(cmdId: String): Option[SMGTree[T]] = {
    if (cmdId == node.id)
      Some(this)
    else if (children.isEmpty)
      None
    else {
      SMGTree.findTreeWithRoot(cmdId, children)
    }
  }

}

// helper static methods for deaing with trees
object SMGTree {

  def findTreeWithRoot[T <: SMGTreeNode](rootId: String, where: Seq[SMGTree[T]]): Option[SMGTree[T]] = {
    var found: Option[SMGTree[T]] = None
    if (where.nonEmpty) {
      where.find { ct =>
        found = ct.findTree(rootId)
        found.isDefined // break if found
      }
    }
    found
  }

  val MAX_SMG_TREE_LEVELS = 10

  def buildTree[T <: SMGTreeNode](leafObjs: Seq[T], parentObjs: Map[String,T]): Seq[SMGTree[T]] = {
    val ret = ListBuffer[SMGTree[T]]()
    var recLevel = 0

    def buildTree(leafs: Seq[SMGTree[T]]): Unit = {
      //      println(leafs)
      val byParent = leafs.groupBy(_.node.parentId.getOrElse(""))
      val myParents = ListBuffer[SMGTree[T]]()
      byParent.foreach { case (prntId, chldrn) =>
        if (prntId == "") {
          // top level
          ret ++= chldrn
        } else {
          val prnt = parentObjs.get(prntId)
          if (prnt.isEmpty) {
            SMGLogger.error(s"SMGTree.buildTree: non existing parent id: $prntId")
            ret ++= chldrn
          } else {
            myParents += SMGTree[T](prnt.get, chldrn)
          }
        }
      }
      if (myParents.nonEmpty) {
        recLevel += 1
        if (recLevel > MAX_SMG_TREE_LEVELS) {
          throw new RuntimeException(s"SMGTree.buildTree: Configuration error - recursion ($recLevel) exceeded $MAX_SMG_TREE_LEVELS")
        }
        buildTree(myParents.toList.sortBy(_.node.id))
        recLevel -= 1
      }
    }
    buildTree(leafObjs.sortBy(_.id).map(o => SMGTree(o, Seq())))
    // consolidate top-level trees sharing the same root
    val topLevelById = ret.toList.groupBy(_.node.id)
    topLevelById.keys.toList.sorted.map { cid =>
      val trees = topLevelById(cid)
      if (trees.tail.isEmpty) {
        trees.head
      } else {
        SMGTree(trees.head.node, trees.flatMap(_.children).sortBy(_.node.id))
      }
    }
  }
}
