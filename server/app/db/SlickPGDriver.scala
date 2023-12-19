package db

import com.github.tminglei.slickpg._
import com.github.tminglei.slickpg.composite.Struct
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import java.time.ZonedDateTime
import scala.reflect.ClassTag

trait SlickPGDriver extends ExPostgresProfile
  with PgArraySupport
  with PgDate2Support
  with PgPlayJsonSupport
  with PgEnumSupport
  with PgCompositeSupport
  with PgNetSupport {
  /* util */
  private def registerVarcharConverter[A : reflect.runtime.universe.TypeTag](from: String => A, to: A => String) = {
    utils.TypeConverters.register[String, A](from)
    utils.TypeConverters.register[A, String](to)
  }

  // Refer to https://github.com/danishin/shiftee/issues/2846#issuecomment-400253650
  // `createCompositeArrayJdbcType` doesn't work
  private object createCompositeArrJdbcType {
    private val util = new PgCompositeSupportUtils(getClass.getClassLoader, emptyMembersAsNull = true)

    def apply[A <: Struct](pgTypeName: String)(implicit classTag: ClassTag[Seq[A]], tag: ClassTag[A], typeTag: scala.reflect.runtime.universe.TypeTag[A]) = {
      new AdvancedArrayJdbcType[A](
        sqlBaseType = s"$pgTypeName[]",
        fromString = util.mkCompositeSeqFromString[A],
        mkString = util.mkStringFromCompositeSeq[A]
      )
    }
  }

  /* register type converters - register when non-primitive type is used inside composite type */
  // library types
  registerVarcharConverter[JsValue](Json.parse, _.toString())
  registerVarcharConverter[JsObject](str => Json.parse(str).as[JsObject], _.toString())
  registerVarcharConverter[JsArray](str => Json.parse(str).as[JsArray], _.toString())
  registerVarcharConverter[ZonedDateTime](str => ZonedDateTime.parse(str, api.date2TzDateTimeFormatter), _.format(api.date2TzDateTimeFormatter))

  /* api provider */
  sealed trait ApiProvider extends API
    with SimpleArrayImplicits
    with DateTimeImplicits
    with PlayJsonImplicits
    with SimpleNetImplicits

    /* Plain SQL */
    with SimpleArrayPlainImplicits
    with PlayJsonPlainImplicits
    with Date2DateTimePlainImplicits
  {
    /* Custom */
    implicit val seqIntColumnType = MappedColumnType.base[Seq[Int], List[Int]](seq => seq.toList, list => list.toSeq)
  }

  /* override */
  override def pgjson = "jsonb"
  override val api = new ApiProvider {}
}

// https://github.com/tminglei/slick-pg/issues/387
object SlickPGDriver extends SlickPGDriver
