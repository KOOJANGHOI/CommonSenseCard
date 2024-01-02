package db.schema

import common.JSON
import db.SlickPGDriver.api._
import play.api.libs.json.Json
import slick.lifted.Tag


case class Category (
                    category_id: Int,
                    name: String
                  )

object Category {
  implicit val writes = JSON.epoch(implicit _w => Json.writes[Category])
}

class Categories(tag: Tag) extends TableLike[Category](tag, "Categories") {
  def category_id = column[Int]("category_id")
  def name = column[String]("name")

  /* Index */
  def categories_category_id_uindex = index("categories_category_id_uindex", category_id)
  def categories_pk = index("categories_pk", category_id, unique = true)

  def * = (category_id, name).<>((Category.apply _).tupled, Category.unapply)
}
