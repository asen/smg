package com.smule.smg.core

/**
  * Definition of a "run stage" used by SMGStagedRunCounter. Represents a max count and proc to call when
  * that count is reached
 *
  * @param maxCount - number of increments this stage represents
  * @param proc - function to call when the stage completes
  */
case class SMGRunStageDef(maxCount: Int, proc: () => Unit)
