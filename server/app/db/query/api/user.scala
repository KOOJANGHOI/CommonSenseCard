package db.query.api

import controllers.api.user.PATCHRatingBody
import db.SlickPGDriver.api._
import db.DBExecutionContext
import db.schema.{Rating, User}
import db.Tables.{ratings, users}
import db.query.api.saveDatum.UserSaveDatum
import task.AppError
import db.query.api.core.BasicStreamingActionExt

import java.time.ZonedDateTime

object user {
  case class FetchUserOpt(device_uuid: String) extends db.Query[Option[User]] {
    protected def prepare(implicit ec: DBExecutionContext) = {
      users
        .filter(u => u.device_uuid === device_uuid)
        .result
        .headOption
    }
  }

  case class FetchUser(user_id: Int) extends db.Query[User] {
    protected def prepare(implicit ec: DBExecutionContext) = {
      users
        .filter(u => u.user_id === user_id)
        .result
        .first(AppError.UserNotFound)
    }
  }

  case class CreateNewUser(datum: UserSaveDatum) extends db.Query[User] {
    protected def prepare(implicit ec: DBExecutionContext) = {
      users.returning(users).+=(User(
        device_uuid = datum.device_uuid,
        bookmark_item_ids = datum.bookmark_item_ids
      ))
    }
  }

  // TODO(impl): update `last_login_at` only when enter app
  case class UpdateUser(user_id: Int, datum: UserSaveDatum) extends db.Query[Unit] {
    protected def prepare(implicit ec: DBExecutionContext) = for {
      _ <- users
        .filter(u => u.user_id === user_id)
        .map(u => (u.last_login_at, u.bookmark_item_ids))
        .update((ZonedDateTime.now(), datum.bookmark_item_ids))
    } yield()
  }

  case class UpdateRating(user_id: Int, body: PATCHRatingBody) extends db.Query[Rating] {
    protected def prepare(implicit ec: DBExecutionContext): DBIO[Rating] = {
      val query = ratings
        .filter(r => r.user_id === user_id && r.item_id === body.item_id)

      for {
        existingRatingOpt <- query.result.headOption

        result <- existingRatingOpt.map { _ =>
          query
            .map(r => (r.rating, r.rated))
            .update((body.rating, true))
            .map(_ => Rating(user_id, body.item_id, body.rating, rated = true))
        }.getOrElse {
          // TODO(impl): All user-item pairs already exists
          (ratings returning ratings)
            .+=(Rating(user_id, body.item_id, body.rating, rated = true))
        }
      } yield result
    }
  }
}
