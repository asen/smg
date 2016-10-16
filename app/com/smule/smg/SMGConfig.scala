package com.smule.smg

/**
  * Created by asen on 11/20/15.
  */
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

   /**
    * Helper map to lookup indexes by id
    */
  val indexesById: Map[String, SMGConfIndex] = indexes.groupBy(ix => ix.id).map( t => (t._1, t._2.head))


}
