package db.schema

import common.JSON
import db.SlickPGDriver.api._
import db.Tables
import play.api.libs.json.Json
import slick.lifted.Tag


case class Rating (
                  user_id: Int,
                  item_id: Int,
                  rating: Int,
                  rated: Boolean
                )

object Rating {
  implicit val writes = JSON.epoch(implicit _w => Json.writes[Rating])
}

class Ratings(tag: Tag) extends TableLike[Rating](tag, "Ratings") {
  def user_id = column[Int]("user_id")
  def item_id = column[Int]("item_id")
  def rating = column[Int]("rating")
  def rated = column[Boolean]("rated")

  /* Primary Key */
  def pk = primaryKey("ratings_pk", (user_id, item_id))

  /* Foreign Key */
  def user = foreignKey("ratings_user_id_fk", user_id, Tables.users)(_.user_id, Restrict, Cascade)
  def item = foreignKey("ratings_item_id_fk", item_id, Tables.items)(_.item_id, Restrict, Cascade)

  /* Index */
  def ratings_user_id_index = index("ratings_user_id_index", user_id)
  def ratings_item_id_index = index("ratings_item_id_index", item_id)

  def * = (user_id, item_id, rating, rated).<>((Rating.apply _).tupled, Rating.unapply)
}
