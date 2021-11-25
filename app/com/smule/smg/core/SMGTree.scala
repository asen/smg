package com.smule.smg.core

/**
  * Created by asen on 11/12/16.
  */


// a tree object which can hold SMGTreeNode descendants
case class SMGTree[T <: SMGTreeNode](node: T, children: Seq[SMGTree[T]]) {

  val isLeaf: Boolean = children.isEmpty

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

  def findTreesMatching(matchFn: (T) => Boolean): Seq[SMGTree[T]] = {
    if (matchFn(this.node)){
      return Seq(this)
    }
    children.flatMap(ct => ct.findTreesMatching(matchFn))
  }

  def id2TreeMap: Map[String, SMGTree[T]] = {
    var ret = Map(node.id -> this)
    children.foreach { c =>
      ret ++= c.id2TreeMap
    }
    ret
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


  private def validateTrees[T <: SMGTreeNode](leafObjs: Seq[T], topLevel: Seq[SMGTree[T]]) = {
    val leafIds = leafObjs.map(_.id).toSet
    val treeLeafIds = topLevel.flatMap(_.leafNodes).map(_.id).toSet
    val missing = leafIds -- treeLeafIds
    if (missing.nonEmpty){
      SMGLogger.error(s"SMGTree.validateTrees: Did not find all leaf nodes, possible circular parents " +
        s"reference. Missing ${missing.size} objects, up to 10 examples: ${missing.take(10)}")
    }
  }

  /**
    * Build all trees from the given leafs and parent objects. parent objects which are not
    * referenced by some leaf (or its parent(s)) are excluded
    * @param leafObjs - all leaf objects
    * @param parentObjs - a map of all available parent objects.
    * @tparam T - the type of the tree node objects, extending SMGTreeNode
    * @return - list of top-level (no parent) trees
    */
  def buildTrees[T <: SMGTreeNode](leafObjs: Seq[T], parentObjs: Map[String,T]): Seq[SMGTree[T]] = {
    if (leafObjs.isEmpty)
      return Seq()
    val allObjs = leafObjs ++ parentObjs.values.toList.distinct
    val allByParent = allObjs.groupBy { o =>
      val pid = o.parentId.getOrElse("")
      if (parentObjs.contains(pid)){
        pid
      } else ""
    }
    val leafTreesById = leafObjs.map(o => SMGTree[T](o, Seq())).groupBy(_.node.id).map { t =>
      if (t._2.tail.nonEmpty) {
        SMGLogger.warn(s"SMGTree.buildTrees: leafs list contains duplicate items: ${t._1} -> ${t._2}")
      }
      (t._1, t._2.head)
    }
    var recLevel = 0

    def myBuildTree(root: T): Option[SMGTree[T]] = {
      if (recLevel > MAX_SMG_TREE_LEVELS) {
        SMGLogger.error(s"SMGTree.buildTrees: myBuildTree: recursion exceeded $MAX_SMG_TREE_LEVELS levels")
        return None
      }
      recLevel += 1
      if (leafTreesById.contains(root.id)){
        recLevel -= 1
        return Some(leafTreesById(root.id))
      }
      val myChidren = allByParent.get(root.id)
      if (myChidren.isEmpty) {
        recLevel -= 1
        return None
      }
      val childTrees = myChidren.get.map { c => myBuildTree(c) }.collect{case Some(x) => x}.sortBy(_.node.id)
      recLevel -= 1
      if (childTrees.isEmpty)
        None
      else
        Some(SMGTree[T](root, childTrees))
    }

    val topLevel = allByParent.getOrElse("", Seq()).map { n => myBuildTree(n) }.collect { case Some(x) => x }.sortBy(_.node.id)
    validateTrees(leafObjs, topLevel)
    topLevel.toList
  }
}
