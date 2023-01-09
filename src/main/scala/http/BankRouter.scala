package http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import com.rockthejvm.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, GetBankAccount, UpdateBalance}
import com.rockthejvm.bank.actors.PersistentBankAccount.{BankAccount, Command, Response}
import com.rockthejvm.bank.actors.PersistentBankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import http.Validation._
import cats.implicits._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreationRequest] = new Validator[BankAccountCreationRequest] {
    override def validate(request: BankAccountCreationRequest): ValidationResult[BankAccountCreationRequest] = {
      val userValidation = validateRequired(request.user, "user")
      val currencyValidation = validateRequired(request.currency, "currency")
      val balanceValidation = validateMinimum(request.balance, 0, "balance")
        .combine(validateMinimumAbs(request.balance, 0.01, "amount"))
      (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreationRequest.apply)
    }
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = new Validator[BankAccountUpdateRequest] {
    override def validate(request: BankAccountUpdateRequest): ValidationResult[BankAccountUpdateRequest] = {
      val currencyValidation = validateRequired(request.currency, "currency")
      val amountValidation = validateMinimumAbs(request.amount, 0.01, "balance")
      (currencyValidation, amountValidation).mapN(BankAccountUpdateRequest.apply)
    }
  }
}

case class FailureResponse(reason: String)

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {

  implicit val timeOut: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(StatusCodes.BadRequest, FailureResponse(failures.toList.map(_.errorMessage).mkString(",")))
    }

  /*
      POST /bank/
        Payload: bank account creation request as JSON
        Response:
          201 Created
          Location: /bank/uuid

       GET /bank/uuid
        Repsonse:
        200 OK
        JSON representation of bank account details

      PUT /bank/uuid
        Payload: (currency, amount) as JSON
        Response:
          1) 200 OK
              - Payload: new bank details as JSON
          2) 404 Not Found
          3) TODO 400 bad request if something has gone wrong

   */
  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          //parse the payload
          entity(as[BankAccountCreationRequest]) { request =>
            //validation
            validateRequest(request) {

              /*
                - convert the requst in a Command for the bank actor
                - send the command to the bank
                - expect a reply
               */
              onSuccess(createBankAccount(request)) {
                // - send back an HTTP response
                case BankAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/bank/$id")) {
                    complete(StatusCodes.Created)
                  }
              }
            }
          }
        }
      } ~
        path(Segment) { id =>
          get {

            /*
            - Send command to the bank
            - expect a reply
             */
            onSuccess(getBankAccount(id)) {
              case GetBankAccountResponse(Some(account)) =>
                complete(account) // Automatically 200 ok
              case GetBankAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"bank account $id does not exist"))
            }
          } ~
            put {
              entity(as[BankAccountUpdateRequest]) { request =>
                // validation
                validateRequest(request) {

                  /*
                    - Transform the request to a Command
                    - send command to the bank
                    - expect a reply
                   */
                  //TODO validate the request here
                  onSuccess(updateBankAccount(id, request)) {
                    // send HTTP response
                    case BankAccountBalanceUpdatedResponse(Success(account)) =>
                      complete(account)
                    case BankAccountBalanceUpdatedResponse(Failure(ex)) =>
                      complete(StatusCodes.BadRequest, FailureResponse(s"""${ex.getMessage}"""))
                  }
                }
              }
              /*
               - send back an HTTP response
               */
            }
          //- send back the HTTP response
        }
    }
}
