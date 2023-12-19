package db.query.api

import db.SlickPGDriver.api._
import db.DBExecutionContext
import task.AppError
import db.schema.User
import db.Tables.users
import db.query.api.core.BasicStreamingActionExt

object user {
  case class FetchUser(user_id: Int) extends db.Query[User] {
    protected def prepare(implicit ec: DBExecutionContext) = {
      users
        .filter(u => u.user_id === user_id)
        .result
        .first(AppError.UserNotFound)
    }
  }
}
