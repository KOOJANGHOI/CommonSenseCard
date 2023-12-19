package db.schema

import common.JSON
import db.SlickPGDriver.api._
import play.api.libs.json.Json

/**
 * Slick provides implicit mappings for common data types, and you need to ensure that they are in scope. For example, if you are working with an Int column, you need to import the implicit TypedType for Int
 */
case class Item (
                  item_id: Int = -1,
                  title: String,
                  contents: String
                )

object Item {
  implicit val writes = JSON.epoch(implicit _w => Json.writes[Item])
}

class Items(tag: Tag) extends TableLike[Item](tag, "Items") {
  def item_id = column[Int]("item_id", O.PrimaryKey, O.AutoInc)
  def title = column[String]("title")
  def contents = column[String]("contents")

  /* Index */
  def items_item_id_uindex = index("items_item_id_uindex", item_id)
  def items_pk = index("items_pk", item_id, unique = true)

  def * = (item_id, title, contents).<>((Item.apply _).tupled, Item.unapply)
}
