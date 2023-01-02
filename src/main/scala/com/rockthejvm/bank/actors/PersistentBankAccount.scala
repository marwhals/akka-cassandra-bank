package com.rockthejvm.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}


//A single bank account
object PersistentBankAccount {

  /*
    event sourcing model -> rebuild steps taken to current state of data
      - fault tolerance
      - can audit entire journey
   */
  import PersistentBankAccount._
  import PersistentBankAccount.Command._
  // commands = messages
  sealed trait Command

  object Command {
    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Double /*can be < 0*/ , replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events = to persist to Cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(newBalance: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // responses
  sealed trait Response
  object Response {

  case class BankAccountCreatedResponse(id: String) extends Response
  case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
  case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }
  /*
    Defining a persistent actor
    - Command handler = message handler => persist and event
    - Event handler = update state
    - state =
   */
  import Response._

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        /*
          - bank created me
          - bank sends me CreateBankAccount
          - I persist BankAccountCreated
          - I update my state
          - reply back to bank with the bankAccountCreatedResponse
          - (the bank surfaces the response to the HTTP server)
         */
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) // Persisted into Cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))
      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount
        //check here for withdrawl
        if (newBalance < 0) // illegal
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))

    }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
        bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // unused but it is important for the persistence model
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )

}