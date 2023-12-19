package db.query.api

import db.SlickPGDriver.api._
import db.DBExecutionContext
import task.AppError
import db.schema.Item
import db.Tables.items
import db.query.api.core.BasicStreamingActionExt

object item {
  // TODO(simon): remove after test
  case class FetchItems(item_ids: Seq[Int]) extends db.Query[Seq[Item]] {
    protected def prepare(implicit ec: DBExecutionContext) =
      items
        .filter(i => i.item_id.inSet(item_ids))
        .result
  }

  case class FetchItem(item_id: Int) extends db.Query[Item] {
    protected def prepare(implicit ec: DBExecutionContext) =
      items
        .filter(i => i.item_id === item_id)
        .result
        .first(AppError.ItemNotFound)
  }
}

