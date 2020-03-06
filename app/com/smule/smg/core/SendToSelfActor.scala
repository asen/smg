package com.smule.smg.core

import akka.actor.{Actor, ActorRef, Props}

class SendToSelfActor(mySelf: ActorRef) extends Actor {
  override def receive: Receive = {
    case x => mySelf ! x
  }
}

object SendToSelfActor {
  def props(mySelf: ActorRef): Props = Props(classOf[SendToSelfActor], mySelf)
}
