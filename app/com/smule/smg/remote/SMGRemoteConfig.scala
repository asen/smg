package com.smule.smg.remote

import com.smule.smg.config.{SMGConfIndex, SMGConfig}
import com.smule.smg.core.{SMGFetchCommand, SMGObjectView}

/**
  * Created by asen on 11/19/15.
  */
/**
  * a representation (subset) of SMG config to be retrieved remotely
  * @param globals - globals map
  * @param viewObjects - list of SMG view objects
  * @param indexes - list of configured index definitions
  * @param remote - the remote this config was retrieved from
  */
case class SMGRemoteConfig(
                            globals: Map[String,String],
                            viewObjects: Seq[SMGObjectView],
                            indexes: Seq[SMGConfIndex],
                            allPreFetches: Seq[SMGFetchCommand],
                            remote: SMGRemote
                          ) extends SMGConfig {

  /**
    * Helper map to lookup objects by id
    */
  val viewObjectsById: Map[String, SMGObjectView] = viewObjects.groupBy(o => o.id).map( t => (t._1, t._2.head) )
}
