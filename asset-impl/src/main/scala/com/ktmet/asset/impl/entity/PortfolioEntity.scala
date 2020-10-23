package com.ktmet.asset.impl.entity

import com.ktmet.asset.api.PortfolioState
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, AggregateEventTagger}

object PortfolioEntity {
  sealed trait Command

  sealed trait Response
  trait ExceptionResponse extends Response

  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 64)
  }


}


case class PortfolioEntity(state: Option[PortfolioState]) {

}
