package controllers

import common.{CommonSenseCookieKey, Config, JWT, JWTClaim}
import common.JWTClaim.{AuthTokenLike, EmptyAuthToken, UserAuthToken}
import common.Util.ImplicitsProvider
import db.DB
import db.schema.User
import db.query.api.user.FetchUser
import play.api.http.{FileMimeTypes, Status}
import play.api.libs.json._
import play.api.mvc._
import play.api.{Environment, Logger}
import play.twirl.api.Html
import task.AppError.CaughtNonFatalException
import task.{AppError, ClientError, ServerError, TaskOps}

import javax.inject.Inject
import scala.reflect.{ClassTag, classTag}

case class ControllerParams @Inject() (controllerComponents: ControllerComponents, env: Environment, db: DB)

trait ControllerLike extends Results with Status with ImplicitsProvider with TaskOps {
  import ControllerLike._

  import scala.language.implicitConversions

  /* Dependencies */
  protected def cp: ControllerParams

  private val Action: ActionBuilder[Request, AnyContent] = cp.controllerComponents.actionBuilder
  protected val parse: PlayBodyParsers = cp.controllerComponents.parsers
  protected implicit val fileMimeTypes: FileMimeTypes = cp.controllerComponents.fileMimeTypes
  protected implicit val env: Environment = cp.env
  protected implicit val db: DB = cp.db

  /* Shortcuts */
  type Task[A] = task.Task[A]
  val Task = task.Task
  type AppError = task.AppError
  val AppError = task.AppError
  val UserAuthToken = common.JWTClaim.UserAuthToken
  val UserAuthTokenKey = CommonSenseCookieKey.UserAuthToken

  /* Implicits */

  /* Util Methods */

  // log errors that cannot be handled via response hook's `onError`
  def logError(message: String): Unit =
    errorLogger.error(s"Error: $message")

  /* Middleware */
  object middleware {
    object authenticateRequest {
      private val EmptyAuthTokenClazz = classOf[EmptyAuthToken]
      private val UserAuthTokenClazz = classOf[UserAuthToken]

      private def authenticateAuthToken[AT <: JWTClaim with AuthTokenLike : Reads : ClassTag](req: RequestHeader, cookieKey: CommonSenseCookieKey.Value = UserAuthTokenKey): Either[ClientError, AT] = {
        def extractAuthTokenFromAuthorizationHeader(): Option[String] = {
          req.headers.get("Authorization")
            .flatMap { authorizationStr =>
              val Seq(scheme, authTokenValues) = authorizationStr.split("\\s", 2).toSeq

              if (scheme == Config.ApiCustomScheme) {
                authTokenValues
                  .split(',')
                  .map{ authTokenValue =>
                    val Seq(key, value) = authTokenValue.split('=').toSeq
                    (key, value)
                  }
                  .toMap
                  .get(cookieKey.toString)
              } else {
                None
              }
            }
        }

        def extractAuthTokenFromCookies(): Option[String] =
          req.cookies.get(cookieKey.toString).map(_.value)

        for {
          jwtString <- extractAuthTokenFromAuthorizationHeader().orElse(extractAuthTokenFromCookies()).toRight(AppError.NoAuthToken(cookieKey))
          authToken <- JWT.verifyAuthToken[AT](jwtString) match {
            case Some(Right(authToken)) => Right(authToken)
            case Some(Left(expires_at)) => Left(AppError.ExpiredAuthToken(cookieKey, s"token expired at $expires_at"))
            case None => Left(AppError.InvalidAuthToken(cookieKey))
          }
        } yield authToken
      }

      // NOTE: Option[AT] here doesn't mean authentication is failing. `None` here simply indicates `EmptyAuthToken`, which is phantom type (thusly, cannot be instantiated to be passed as argument)
      // We lose a bit of Type Safety here by using `asInstanceOf` to conform to generic but cannot really think of any other way to implement this.
      def apply[AT <: AuthTokenLike : ClassTag](req: RequestHeader): Task[Option[AT]] = {
        classTag[AT].runtimeClass match {
          case EmptyAuthTokenClazz =>
            Task.just(None)

          case UserAuthTokenClazz =>
            for {
              userAuthToken <- authenticateAuthToken[UserAuthToken](req) match {
                case Right(userAuthToken) =>
                  Task.just(userAuthToken)

                case Left(clientError) =>
                  Task.error(clientError)
              }
            } yield Some(userAuthToken.asInstanceOf[AT])
        }
      }
    }

    private def handleRequest[A, AT <: JWTClaim with AuthTokenLike : ClassTag : Reads](bodyParser: BodyParser[A])(block: PartialFunction[(Request[A], Long, Option[AT]), Task[Result]]): Action[A] = {
      Action.async(bodyParser) { request =>
        val requestedAt = System.currentTimeMillis()

        val task = for {
          // NOTE: authTokenOpt here doesn't mean authentication is failing. `None` here simply indicates `EmptyAuthToken`, which is phantom type (thusly, cannot be instantiated to be passed as argument)
          authTokenOpt <- authenticateRequest[AT](request)
            .on(
              // when user fails to authenticate, take it as unauthenticated for logging request
              onError = _ => logReq(request, None),
              onSuccess = authTokenOpt => logReq(request, authTokenOpt)
            )

          result <- {
            try {
              block((request, requestedAt, authTokenOpt))
            } catch {
              case scala.util.control.NonFatal(e) =>
                Task.error[Result](CaughtNonFatalException(e))
            }
          }
            .onError(e => logRes(request, requestedAt, Left(e), authTokenOpt))
        } yield result

        task.run(e => e.result, identity)
      }
    }

    object Unauthenticated {
      def apply[A](bodyParser: BodyParser[A] = parse.empty)(block: A => RequestHeader => Task[Result]): Action[A] =
        handleRequest[A, EmptyAuthToken](bodyParser) { case (req, requestedAt, _) =>
          for {
            result <- block(req.body)(req)
          } yield {
            logRes(req, requestedAt, Right((result.header.status, "Result")), None)
            result
          }
        }

      // NOTE: All API endpoints using `Unauthenticated.HTML` is website part of our code where we always have implicit `lang` available.
      // NOTE: We can take advantage of it and set cookie value for preferred lang to use for `extractLang`
      def HTML[A](bodyParser: BodyParser[A] = parse.empty)(block: A => RequestHeader => Task[Html])(): Action[A] =
        handleRequest[A, EmptyAuthToken](bodyParser) { case (req, requestedAt, _) =>
          for {
            html <- block(req.body)(req)
          } yield {
            logRes(req, requestedAt, Right((200, "HTML")), None)
            Ok(html)
          }
        }

      def JSON[A](bodyParser: BodyParser[A] = parse.empty)(block: A => RequestHeader => Task[JsObject]): Action[A] =
        handleRequest[A, EmptyAuthToken](bodyParser) { case (req, requestedAt, None) =>
          for {
            json <- block(req.body)(req)
          } yield {
            logRes(req, requestedAt, Right((200, json.toString())), None)
            Ok(json)
          }
        }
    }

    object Authenticated {
      def apply[A](bodyParser: BodyParser[A] = parse.empty)(block: (UserAuthToken, A) => RequestHeader => Task[Result]): Action[A] =
        handleRequest[A, UserAuthToken](bodyParser) { case (req, requestedAt, Some(userAuthToken)) =>
          for {
            result <- block(userAuthToken, req.body)(req)
          } yield {
            logRes(req, requestedAt, Right((200, "Result")), Some(userAuthToken))
            result
          }
        }

      def JSON[A](bodyParser: BodyParser[A] = parse.empty)(block: (UserAuthToken, A) => RequestHeader => Task[JsObject]): Action[A] =
        handleRequest[A, UserAuthToken](bodyParser) { case (req, requestedAt, Some(userAuthToken)) =>
          for {
            json <- block(userAuthToken, req.body)(req)
          } yield {
            logRes(req, requestedAt, Right((200, json.toString())), Some(userAuthToken))
            Ok(json)
          }
        }

      object User {
        private def authorizeUser(userAuthToken: UserAuthToken, req: RequestHeader): Task[User] = for {
          user <- db.exec(FetchUser(userAuthToken.user_id))
        } yield user

        def apply[A]()(bodyParser: BodyParser[A] = parse.empty)(block: (User, A) => RequestHeader => Task[Result]): Action[A] =
          handleRequest[A, UserAuthToken](bodyParser) { case (req, requestedAt, Some(userAuthToken)) =>
            for {
              user <- authorizeUser(userAuthToken, req)
              result <- block(user, req.body)(req)
            } yield {
              logRes(req, requestedAt, Right((200, "Result")), Some(userAuthToken))
              result
            }
          }

        def JSON[A]()(bodyParser: BodyParser[A] = parse.empty)(block: (User, A) => RequestHeader => Task[JsObject]): Action[A] =
          handleRequest[A, UserAuthToken](bodyParser) { case (req, requestedAt, Some(userAuthToken)) =>
            for {
              user <- authorizeUser(userAuthToken, req)
              json <- block(user, req.body)(req)
            } yield {
              logRes(req, requestedAt, Right((200, json.toString())), Some(userAuthToken))
              Ok(json)
            }
          }
      }
    }
  }
}

object ControllerLike {
  // Response data is not that useful for debugging and it takes up much space
  private val ResLoggerDebugResponseDataCharLimitOnProduction = 200

  private val reqLogger = Logger("api.req")
  private val resLogger = Logger("api.res")
  private val errorLogger = Logger("api.error")

  private object getUserInfoStr {
    private def getAuthInfoStr[AT <: AuthTokenLike](authToken: AT): String =
      authToken match {
        case UserAuthToken(user_id, _, _) => s"${Config.UserIdLogKey}:$user_id"
      }

    def apply[AT <: AuthTokenLike](request: Request[_], authTokenOpt: Option[AT]) = (authTokenOpt) match {
      case Some(authToken) => s" (${request.remoteAddress},${getAuthInfoStr(authToken)})"
      case None => s" (${request.remoteAddress})"
    }
  }

  def logReq[AT <: AuthTokenLike](req: Request[_], authTokenOpt: Option[AT]): Unit = {
    lazy val userInfoStr = getUserInfoStr(req, authTokenOpt)

    lazy val data = req.method match {
      case "GET" | "DELETE" => if (req.queryString.nonEmpty) Some(req.queryString.toString()) else None
      case "POST" | "PUT" | "PATCH" => Some(req.body.toString)
      case _ => None
    }

    reqLogger.debug(data match {
      case Some(d) => s"${req.method} ${req.uri}$userInfoStr: $d"
      case None => s"${req.method} ${req.uri}$userInfoStr"
    })
  }

  def logRes[AT <: AuthTokenLike](req: Request[_], requestedAt: Long, eitherErrorOrResponse: Either[AppError, (Int, String)], authTokenOpt: Option[AT]): Unit = {
    lazy val responseTime = System.currentTimeMillis() - requestedAt
    lazy val userInfoStr = getUserInfoStr(req, authTokenOpt)

    eitherErrorOrResponse match {
      case Left(e) =>
        lazy val errorStatusCode = e.result.header.status

        e match {
          case _: ClientError => resLogger.warn(s"${req.method} ${req.uri} $errorStatusCode$userInfoStr+$responseTime: $e")
          case _: ServerError => resLogger.error(s"${req.method} ${req.uri} $errorStatusCode$userInfoStr+$responseTime: $e")
        }

      case Right((statusCode, responseDataStr)) =>
        // NOTE: even though response is not passed as `Error` in `Task`, it could still be an error (the ones that are handled specifically by the application code via `materializeError` or `mergeError`)
        statusCode match {
          case _ if statusCode < 400 =>
            lazy val trimmedResponseDataStr = if (responseDataStr.length > ResLoggerDebugResponseDataCharLimitOnProduction) responseDataStr.take(ResLoggerDebugResponseDataCharLimitOnProduction) else responseDataStr
            resLogger.debug(s"${req.method} ${req.uri} $statusCode$userInfoStr+$responseTime: $trimmedResponseDataStr")

          case _ if statusCode < 500 =>
            resLogger.warn(s"${req.method} ${req.uri} $statusCode$userInfoStr+$responseTime: $responseDataStr")

          case _ =>
            resLogger.error(s"${req.method} ${req.uri} $statusCode$userInfoStr+$responseTime: $responseDataStr")
        }
    }
  }
}
