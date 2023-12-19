package db

import db.schema._
import slick.lifted.TableQuery

trait TablesProvider {
  val items = TableQuery[Items]
  val users = TableQuery[Users]
}

object Tables extends TablesProvider
