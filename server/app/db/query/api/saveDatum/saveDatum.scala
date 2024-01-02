package db.query.api.saveDatum

import common.JSON
import db.schema.{Items, Users}
import play.api.libs.json.Reads

case class UserSaveDatum(device_uuid: String, bookmark_item_ids: Seq[Int])

object UserSaveDatum {
  def columns(u: Users) = (
    u.device_uuid,
    u.bookmark_item_ids
  )

  implicit val backwardCompatibleReads: Reads[UserSaveDatum] = JSON.backwardCompatibleReads { json =>
    val datum = UserSaveDatum(
      device_uuid = json.get[String]("device_uuid"),
      bookmark_item_ids = json.get[Seq[Int]]("bookmark_item_ids")
    )

    datum
  }
}

case class ItemSaveDatum(title: String, contents: String)

object ItemSaveDatum {
  def columns(i: Items) = (
    i.title,
    i.contents
  )

  implicit val backwardCompatibleReads: Reads[ItemSaveDatum] = JSON.backwardCompatibleReads { json =>
    val datum = ItemSaveDatum(
      title = json.get[String]("title"),
      contents = json.get[String]("contents")
    )

    datum
  }
}