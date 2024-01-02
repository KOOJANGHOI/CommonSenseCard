package common

import play.api.Logger
import play.api.libs.json._

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ExtractJson(val jsValue: JsValue) {
  def as[A : Reads] = jsValue.as[A]
  def asOpt[A : Reads] = jsValue.asOpt[A]

  def get[A : Reads](key: String): A = (jsValue \ key).as[A]
  def getOpt[A : Reads](key: String): Option[A] = (jsValue \ key).asOpt[A]

  // use this to maintain backward compatibility
  def getOrCompat[A : Reads](key: String, compatibleValue: => A): A = {
    jsValue \ key match {
      case JsDefined(value) => value.asOpt[A].getOrElse(compatibleValue)
      case JsUndefined() => compatibleValue
    }
  }

  def idx[A : Reads](index: Int): A = (jsValue \ index).as[A]
  def idxOpt[A : Reads](index: Int): Option[A] = (jsValue \ index).asOpt[A]
}

object JSON {
  private val logger = Logger("util.json")

  def backwardCompatibleReads[A](f: ExtractJson => A): Reads[A] = {
    Reads { json =>
      try {
        JsSuccess(f(new ExtractJson(json)))
      } catch {
        case exception @ JsResultException(errors) =>
          logger.warn(s"js result exception while reading json: $errors - $json")
          JsError(exception.toString)

        case exception: IllegalArgumentException =>
          logger.warn(s"requirement exception while reading json: ${exception.getMessage} - $json")
          JsError(exception.toString)

        case scala.util.control.NonFatal(error) =>
          logger.error(s"Uncaught Error while reading json: ${error.getMessage} - $json")
          JsError(error.toString)
      }
    }
  }
  object epoch {
    private val zonedDateTimeWrites: Writes[ZonedDateTime] = Writes(zonedDateTime => JsString(zonedDateTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)))

    def apply[A](f: Writes[ZonedDateTime] => OWrites[A]): OWrites[A] = f(zonedDateTimeWrites)
    def writes[A](f: Writes[ZonedDateTime] => A => JsValue): Writes[A] = Writes(a => f(zonedDateTimeWrites)(a))
    def writesO[A](f: Writes[ZonedDateTime] => A => JsObject): OWrites[A] = OWrites(a => f(zonedDateTimeWrites)(a))
  }
}
