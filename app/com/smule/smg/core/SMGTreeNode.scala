package com.smule.smg.core

/**
  * Created by asen on 11/12/16.
  */

// Intrerface for representing a tree node
trait SMGTreeNode {
  val id: String
  def parentId: Option[String]
}
