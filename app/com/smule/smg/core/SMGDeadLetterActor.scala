package com.smule.smg.core

/**
  * Created by asen on 2/10/16.
  */

import akka.actor.{Actor, DeadLetter}

class SMGDeadLetterActor extends Actor {
  val log = SMGLogger

  def receive = {
    case d: DeadLetter => log.error("SMGDeadLetterActor: dead letter detected: " + d)
  }
}
