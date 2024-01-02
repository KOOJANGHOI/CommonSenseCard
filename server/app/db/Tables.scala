package db

import db.schema._
import slick.lifted.TableQuery

trait TablesProvider {
  val items = TableQuery[Items]
  val users = TableQuery[Users]
  val ratings = TableQuery[Ratings]
  val categories = TableQuery[Categories]
  val itemsCategories = TableQuery[ItemsCategories]
}

object Tables extends TablesProvider
