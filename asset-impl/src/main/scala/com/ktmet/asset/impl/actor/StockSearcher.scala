package com.ktmet.asset.impl.actor

import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import com.rklaehn.radixtree.RadixTree

object StockSearcher {

  sealed trait SearchType
  case object Name extends SearchType
  case object Code extends SearchType

  sealed trait Command

  def apply(searchType: SearchType):Behavior[Command] =
    Behaviors.setup[Command]{ context =>
      Behaviors.supervise[Command]{

        def init:Behavior[Command] = Behaviors.receiveMessage{
          case _ => Behaviors.same
        }

        def ing(radixTree: RadixTree[String, String]):Behavior[Command] = Behaviors.receiveMessage{
          case _ => Behaviors.same
        }




        Behaviors.same
      }.onFailure[Exception](SupervisorStrategy.restart)
    }
}
