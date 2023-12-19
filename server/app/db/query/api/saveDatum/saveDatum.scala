package db.query.api.saveDatum

import common.JSON
import db.schema.{Items, Users}
import play.api.libs.json.Reads

import java.time.ZonedDateTime

case class UserSaveDatum(
                          device_uuid: String,
                          created_at: ZonedDateTime,
                          last_login_at: ZonedDateTime,
                          bookmark_item_ids: Seq[Int]
                        )
object UserSaveDatum {
  def columns(u: Users) = (
    u.device_uuid,
    u.created_at,
    u.last_login_at,
    u.bookmark_item_ids
  )
}

case class ItemSaveDatum(
                          title: String,
                          contents: String
                        )
object ItemSaveDatum {
  def columns(i: Items) = (
    i.title,
    i.contents
  )

  final val backwardCompatibleReads: Reads[ItemSaveDatum] = JSON.backwardCompatibleReads { json =>
    val datum = ItemSaveDatum(
      title = json.get[String]("title"),
      contents = json.get[String]("contents")
    )

    datum
  }
}
