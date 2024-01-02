package db.schema

import common.JSON
import db.SlickPGDriver.api._
import db.UniqueIndex_UPDATE_DB
import play.api.libs.json.Json
import task.AppError

import java.time.ZonedDateTime

/**
 * Slick provides implicit mappings for common data types, and you need to ensure that they are in scope. For example, if you are working with an Int column, you need to import the implicit TypedType for Int
 */
case class User (
                  user_id: Int = -1,
                  device_uuid: String,
                  created_at: ZonedDateTime = ZonedDateTime.now(),
                  last_login_at: ZonedDateTime = ZonedDateTime.now(),
                  bookmark_item_ids: Seq[Int] = Seq.empty
                )

object User {
  implicit val writes = JSON.epoch(implicit _w => Json.writes[User])
}

class Users(tag: Tag) extends TableLike[User](tag, "Users") {
  def user_id = column[Int]("user_id", O.PrimaryKey, O.AutoInc)
  def device_uuid = column[String]("device_uuid")
  def created_at = column[ZonedDateTime]("created_at")
  def last_login_at = column[ZonedDateTime]("last_login_at")
  def bookmark_item_ids = column[Seq[Int]]("bookmark_item_ids")

  /* Index */
  def users_user_id_uindex = index("users_user_id_uindex", user_id)
  def users_pk = index("users_pk", user_id, unique = true)

  def * = (user_id, device_uuid, created_at, last_login_at, bookmark_item_ids).<>((User.apply _).tupled, User.unapply)
}

object Users {
  val DeviceUUIDUniqueIDX = UniqueIndex_UPDATE_DB("Users_device_uuid_index", AppError.CannotCreateUserWithDuplicateDeviceUUID)
}
