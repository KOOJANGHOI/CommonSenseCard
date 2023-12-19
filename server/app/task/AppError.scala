package task

import com.google.common.base.Throwables
import common.CommonSenseCookieKey
import play.api.libs.json.{JsPath, Json, JsonValidationError}
import play.api.mvc.Results._
import play.api.mvc.Result

/**
 * AppError is simple `Throwable` that wraps another **cause** `Throwable`.
 */
sealed trait AppError extends Throwable {
  def result: Result
}

sealed abstract class ClientError(val status: Status, errorMessageParams: (String, String)*) extends AppError {
  private val ParamsDelimiter = '|'

  // eg. "EmailNotConfirmed"
  // eg. "LocationHasNoIp|{"location_name": "판교"}"
  lazy val errorMessage = {
    // case object appends `$`
    val errorKey = getClass.getSimpleName.split("""\$""").head

    val errorParamsJsonOpt = {
      val fieldsMap = errorMessageParams.toMap
      if (fieldsMap.isEmpty) None
      else Some(Json.toJson(fieldsMap).toString())
    }

    errorKey + errorParamsJsonOpt.fold("")(p => s"$ParamsDelimiter$p")
  }

  lazy val result = status(errorMessage)

  override def toString = s"ClientError:: ${getClass.getSimpleName} :/$errorMessage/"
}

sealed abstract class ServerError extends AppError {
  lazy val result = InternalServerError
  def cause: Throwable
  override def toString = {
    s"ServerError:: ${getClass.getSimpleName}" +
      s"""
         |-----------------------------------------------
         |${Throwables.getStackTraceAsString(cause)}
         |-----------------------------------------------
                        """.stripMargin
  }
}

object AppError {
  ////////////////////////////////////////////
  // Custom Errors
  ////////////////////////////////////////////
  protected case object TaskFilterError extends Error("Task filter failed. This is not intended.")
  case class ActorUnhandledMessageError(message: Any) extends Error(s"Actor cannot handle message: $message")

  ////////////////////////////////////////////
  // Custom Exceptions
  ////////////////////////////////////////////
  case class JsonParseException(reasons: Seq[(JsPath, Seq[JsonValidationError])]) extends RuntimeException(s"Failed to parse JSON: $reasons")
  case class CaughtDBException(queryName: String, cause: Throwable) extends RuntimeException(s"Caught DB Exception ($queryName): $cause")
  ////////////////////////////////////////////
  // AppError - ClientError
  ////////////////////////////////////////////
  /* SQL ERROR */
  /* Unique Index Constraints */
  case object CannotCreateUserWithDuplicateDeviceUUID extends ClientError(Conflict)

  // Authentication Errors
  case class NoAuthToken(cookieKey: CommonSenseCookieKey.Value) extends ClientError(Unauthorized, "cookieKey" -> cookieKey.toString)
  case class InvalidAuthToken(cookieKey: CommonSenseCookieKey.Value, message: String = "") extends ClientError(Unauthorized, "cookieKey" -> cookieKey.toString, "message" -> message)
  case class ExpiredAuthToken(cookieKey: CommonSenseCookieKey.Value, message: String = "") extends ClientError(Unauthorized, "cookieKey" -> cookieKey.toString, "message" -> message)

  ////////////////////////////////////////////
  // AppError - ServerError
  ////////////////////////////////////////////
  case class DB(cause: Throwable) extends ServerError
  case class Crypto(cause: Throwable) extends ServerError
  case object TaskFilter extends ServerError { val cause = TaskFilterError }

  case class CaughtNonFatalException(cause: Throwable) extends ServerError

  /* Requested Model Not Found */
  case object ItemNotFound extends ClientError(NotFound)
  case object UserNotFound extends ClientError(NotFound)
}
