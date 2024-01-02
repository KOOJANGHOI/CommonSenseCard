package common

import play.api.data.Mapping
import play.api.mvc.{Cookie, DiscardingCookie, QueryStringBindable, Result}
import play.api.Logger
import task.Task

import java.time.{Duration, ZonedDateTime}
import java.util.regex.Pattern
import scala.concurrent.Await
import scala.util.matching.Regex

object CommonSenseCookieKey extends Enumeration {
  val UserAuthToken = Value(Config.UserAuthTokenKey)
}

object Util {
  private val logger = Logger("util.util")

  /* APP CONSTANTS */

  val zonedDateTimeOrdering: Ordering[ZonedDateTime] = Ordering.fromLessThan(_ isBefore _)

  /* UTILITY METHODS */
  object ParseInt {
    def unapply(s: String): Option[Int] =
      try Some(s.toInt) catch {
        case _: NumberFormatException => None
      }
  }

  /* IMPLICITS */
  trait ImplicitsProvider {
    implicit val implicitZonedDateTimeOrdering = zonedDateTimeOrdering

    implicit class StringExt(string: String) {
      // Refer to https://stackoverflow.com/a/10861856/3891342
      // This is different from `String.replaceFirst`
      def replaceOnce(searchString: String, replacement: String) =
        string.replaceFirst(Pattern.quote(searchString), replacement)
    }

    implicit class DoubleExt(double: Double) {
      def isBetween(start: Double, end: Double): Boolean = double >= start && double <= end
    }

    implicit class VectorExt[A](vector: Vector[A]) {
      def multiSpan(p: A => Boolean): Vector[Vector[A]] = {
        val buf = collection.mutable.ArrayBuffer(collection.mutable.ArrayBuffer[A]())
        var lastInnerBufIdx = 0

        vector.foreach { a =>
          if (p(a)) {
            buf.append(collection.mutable.ArrayBuffer(a))
            lastInnerBufIdx = lastInnerBufIdx + 1
          } else {
            buf(lastInnerBufIdx).append(a)
          }
        }

        buf.filter(_.nonEmpty).map(_.toVector).toVector
      }
    }

    implicit class MappingExt[A](mapping: Mapping[A]) {
      import scala.concurrent.duration._

      /**
       * block `Task[A]` operation to use synchronous `verifying` method.
       */
      def verifyingSync(error: => String, constraint: A => Task[Boolean]): Mapping[A] =
        mapping.verifying(error, { a =>
          val task = constraint(a)
          val future = task.run({ e => logger.error(s"Task error while blocking in `mapping.verifying`: $e"); false }, identity)
          Await.result(future, 10.seconds)
        })
    }

    implicit class RegexExt(regex: Regex) {
      def matches(input: String): Boolean = regex.pattern.matcher(input).matches()
    }

    implicit class ResultExt(result: Result) {
      def withSecureCookie(cookieKey: CommonSenseCookieKey.Value = CommonSenseCookieKey.UserAuthToken, cookieValue: String, maxAgeDurationOpt: Option[Duration]): Result = {
        result.withCookies(Cookie(cookieKey.toString, cookieValue, maxAge = maxAgeDurationOpt.map(_.getSeconds.toInt), secure = true, httpOnly = true))
      }

      def withoutCookie(cookieKey: CommonSenseCookieKey.Value = CommonSenseCookieKey.UserAuthToken): Result = {
        result.discardingCookies(DiscardingCookie(cookieKey.toString, secure = true), DiscardingCookie(cookieKey.toString, secure = false))
      }
    }
  }
  object Implicits extends ImplicitsProvider

  /* UTILITY TRAITS */
  /// More succint version of QueryStringBindable by not accounting for list version.
  trait NonListQueryStringBindable[A] extends QueryStringBindable[A] {
    private val stringBinder: QueryStringBindable[String] = QueryStringBindable.bindableString

    def bindOne(param: String): Either[String, A]
    def unbindOne(value: A): String

    def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, A]] = stringBinder.bind(key, params).map(_.flatMap(s => bindOne(s)))
    def unbind(key: String, value: A): String = key + "=" + unbindOne(value)
  }
}
