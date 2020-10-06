package com.smule.smg.config

import com.smule.smg.core.{SMGFetchCommand, SMGObjectView}
import com.smule.smg.remote.SMGRemote

/**
  * An interface representing SMG local or remote configuration objects
  */
trait SMGConfig {
  /**
    * map of global String vars (name -> value)
    */
  val globals: Map[String,String]

  /**
    * List of configured SMGObjectViews (each representing a graph)
    */
  def viewObjects: Seq[SMGObjectView]

  /**
    * Helper map to lookup objects by id
    */
  def viewObjectsById: Map[String, SMGObjectView] //= objects.groupBy(o => o.id).map( t => (t._1, t._2.head) )

  /**
    * List of configured SMG indexes
    */
  val indexes: Seq[SMGConfIndex]

  // exposed for search
  val allPreFetches: Seq[SMGFetchCommand]
   /**
    * Helper maps to lookup indexes by id/local id
    */
  val indexesById: Map[String, SMGConfIndex] = indexes.groupBy(ix => ix.id).map( t => (t._1, t._2.head))

  val indexesByLocalId: Map[String, SMGConfIndex] = indexes.groupBy(ix => SMGRemote.localId(ix.id)).map(t => (t._1, t._2.head))

}
