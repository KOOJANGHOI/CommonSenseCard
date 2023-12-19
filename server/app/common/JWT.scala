package common

import common.JWTClaim.AuthTokenLike
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.{JwtException, Jwts, SignatureAlgorithm}
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.time.{Duration, ZonedDateTime}

sealed trait JWTClaim
object JWTClaim {
  ////////////////////////////////////////////
  // AuthToken
  ////////////////////////////////////////////
  sealed trait AuthTokenLike {
    def created_at: ZonedDateTime
    def expires_at: ZonedDateTime
  }

  // phantom type. used to indicate the absence of auth token
  sealed trait EmptyAuthToken extends JWTClaim with AuthTokenLike
  object EmptyAuthToken {
    implicit val formats: Format[EmptyAuthToken] = new Format[EmptyAuthToken] {
      def reads(json: JsValue) = JsError()
      def writes(o: EmptyAuthToken) = JsNull
    }
  }

  case class UserAuthToken(user_id: Int, created_at: ZonedDateTime, expires_at: ZonedDateTime) extends JWTClaim with AuthTokenLike
  object UserAuthToken {
    // set `employee_auth_token` cookie to always expire in 1 year because this token is always checked with real auth token, which is `account_auth_token`.
    // `employee_auth_token` is nothing more than an assertion that this account has has access to this `employee_id`
    final val Expiry = Duration.ofDays(365) // 1 year

    def from(user_id: Int): UserAuthToken = {
      val now = ZonedDateTime.now()

      UserAuthToken(
        user_id = user_id,
        created_at = now,
        expires_at = now.plus(Expiry)
      )
    }

    implicit val formats = OFormat[UserAuthToken](
      r = JSON.backwardCompatibleReads { json =>
        json.getOpt[ZonedDateTime]("created_at") match {
          case Some(created_at) =>
            UserAuthToken(
              user_id = json.get[Int]("user_id"),
              created_at = created_at,
              expires_at = json.get[ZonedDateTime]("expires_at")
            )

          case None =>
            val now = ZonedDateTime.now()

            UserAuthToken(
              user_id = json.get[Int]("user_id"),
              created_at = now,
              expires_at = now.plus(Expiry)
            )
        }
      },
      w = Json.writes[UserAuthToken]
    )
  }

  implicit class JWTClaimExt[A <: JWTClaim](jwtClaim: A) {
    def toJSONString(implicit evt: Writes[A]): String = Json.toJson(jwtClaim).toString()
  }
}

object JWT {
  import scala.reflect._

  private lazy val logger = Logger("jwt")
  private lazy val jwtSecretKey = Keys.hmacShaKeyFor(Config.JwtSecret.getBytes(StandardCharsets.UTF_8))
  private lazy val jwtParser = Jwts.parserBuilder()
    .setSigningKey(jwtSecretKey)
    .build()

  def sign[JC <: JWTClaim : Writes](jc: JC): String = {
    Jwts.builder()
      .setPayload(Json.toJson(jc).toString())
      .signWith(jwtSecretKey, SignatureAlgorithm.HS256)
      .compact()
  }

  def verify[JC <: JWTClaim : Reads : ClassTag](jwtString: String): Option[JC] = {
    try {
      val jwsClaims = jwtParser
        .parseClaimsJws(jwtString)
        .getBody

      var jsObject = JsObject.empty
      jwsClaims.forEach(new java.util.function.BiConsumer[String, AnyRef]() {
        def accept(key: String, value: AnyRef) = value match {
          case integer: java.lang.Integer => jsObject = jsObject.+(key -> JsNumber(BigDecimal(integer)))
          case double: java.lang.Double => jsObject = jsObject.+(key -> JsNumber(BigDecimal(double)))
          case string: java.lang.String => jsObject = jsObject.+(key -> JsString(string))
          case boolean: java.lang.Boolean => jsObject = jsObject.+(key -> JsBoolean(boolean))
          case null => jsObject = jsObject.+(key -> JsNull)
        }
      })

      jsObject.validate[JC].asOpt
    } catch {
      case ex: JwtException =>
        val jwtClaimClazz = classTag[JC].runtimeClass
        logger.error(s"Error while parsing jwt to ($jwtClaimClazz) using ($jwtString): $ex")
        None
    }
  }

  def verifyAuthToken[AT <: JWTClaim with AuthTokenLike : Reads : ClassTag](jwtString: String): Option[Either[ZonedDateTime, AT]] = {
    verify[AT](jwtString) match {
      case Some(authToken) =>
        val now = ZonedDateTime.now()

        if (now.isBefore(authToken.expires_at)) {
          Some(Right(authToken))
        } else {
          Some(Left(authToken.expires_at))
        }

      case None =>
        None
    }
  }
}
