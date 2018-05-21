package hmda.persistence.institutions

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Props}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  EntityRef,
  EntityTypeKey
}
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import com.typesafe.config.ConfigFactory
import hmda.model.institution.Institution

object InstitutionPersistence {

  final val name = "Institution"

  sealed trait InstitutionCommand
  sealed trait InstitutionEvent
  case class CreateInstitution(i: Institution,
                               replyTo: ActorRef[InstitutionCreated])
      extends InstitutionCommand
  case class InstitutionCreated(i: Institution) extends InstitutionEvent
  case class ModifyInstitution(i: Institution,
                               replyTo: ActorRef[InstitutionModified])
      extends InstitutionCommand
  case class InstitutionModified(i: Institution) extends InstitutionEvent
  case class DeleteInstitution(LEI: String,
                               replyTo: ActorRef[InstitutionDeleted])
      extends InstitutionCommand
  case class InstitutionDeleted(LEI: String) extends InstitutionEvent
  case class Get(replyTo: ActorRef[Option[Institution]])
      extends InstitutionCommand
  case object InstitutionStop extends InstitutionCommand

  val config = ConfigFactory.load()

  val ShardingTypeName = EntityTypeKey[InstitutionCommand](name)
  val shardNumber = config.getInt("hmda.institutions.shardNumber")

  case class InstitutionState(institution: Option[Institution]) {
    def isEmpty: Boolean = institution.isEmpty
  }

  def behavior(entityId: String): Behavior[InstitutionCommand] =
    PersistentBehaviors
      .receive[InstitutionCommand, InstitutionEvent, InstitutionState](
        persistenceId = s"$name-$entityId",
        initialState = InstitutionState(None),
        commandHandler = commandHandler,
        eventHandler = eventHandler
      )
      .snapshotEvery(1000)
      .withTagger(_ => Set(name))

  val commandHandler
    : CommandHandler[InstitutionCommand, InstitutionEvent, InstitutionState] = {
    (ctx, state, cmd) =>
      cmd match {
        case CreateInstitution(i, replyTo) =>
          Effect.persist(InstitutionCreated(i)).andThen {
            ctx.log.debug(s"Institution Created: ${i.toString}")
            replyTo ! InstitutionCreated(i)
          }
        case ModifyInstitution(i, replyTo) =>
          Effect.persist(InstitutionModified(i)).andThen {
            ctx.log.debug(s"Institution Modified: ${i.toString}")
            replyTo ! InstitutionModified(i)
          }
        case DeleteInstitution(lei, replyTo) =>
          Effect.persist(InstitutionDeleted(lei)).andThen {
            ctx.log.debug(s"Institution Deleted: $lei")
            replyTo ! InstitutionDeleted(lei)
          }
        case Get(replyTo) =>
          replyTo ! state.institution
          Effect.none
        case InstitutionStop =>
          Effect.stop
      }
  }

  def createShardedInstitution(
      system: ActorSystem[_],
      entityId: String): EntityRef[InstitutionCommand] = {
    ClusterSharding(system).spawn[InstitutionCommand](
      entityId => behavior(entityId),
      Props.empty,
      ShardingTypeName,
      ClusterShardingSettings(system),
      maxNumberOfShards = shardNumber,
      handOffStopMessage = InstitutionStop
    )

    ClusterSharding(system).entityRefFor(ShardingTypeName, entityId)
  }

  val eventHandler
    : (InstitutionState, InstitutionEvent) => (InstitutionState) = {
    case (state, InstitutionCreated(i))  => state.copy(Some(i))
    case (state, InstitutionModified(i)) => modifyInstitution(i, state)
    case (state, InstitutionDeleted(_))  => state.copy(None)
  }

  private def modifyInstitution(institution: Institution,
                                state: InstitutionState): InstitutionState = {
    if (state.isEmpty) {
      state
    } else {
      if (institution.LEI == state.institution.get.LEI) {
        state.copy(Some(institution))
      } else {
        state
      }
    }
  }

}
