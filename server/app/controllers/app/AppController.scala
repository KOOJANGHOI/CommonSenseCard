package controllers.app

import controllers.api.user.PATCHRatingBody
import controllers.{ControllerLike, ControllerParams}
import db.query.api.item.FetchItem
import db.query.api.saveDatum.UserSaveDatum
import db.query.api.user.{CreateNewUser, FetchUser, FetchUserOpt, UpdateRating, UpdateUser}
import play.api.libs.json.Json

import javax.inject.Inject

// TODO(deploy): Refactor
class AppController @Inject() (protected val cp: ControllerParams) extends ControllerLike {
  def GETIndex() = middleware.Unauthenticated.HTML() ((_) => implicit req => Task.just(views.html.index("Welcome")))

  def GETItemContentsHtml() = middleware.Unauthenticated() { (_) => implicit req =>
    for {
      result <- {
        for {
          item <- db.exec(FetchItem(2))
        } yield Ok(views.html.index(item.contents))
      }
    } yield result
  }

  def GETItem(item_id: Int) = middleware.Unauthenticated.JSON() { (_) => implicit req =>
    for {
      item <- db.exec(FetchItem(item_id))
    } yield Json.obj("item" -> item)
  }

  def PATCHRating(user_id: Int) = middleware.Unauthenticated.JSON(parse.json[PATCHRatingBody]) { (body) => implicit req =>
    for {
      rating <- db.exec(UpdateRating(user_id, body))
    } yield Json.obj("rating" -> rating)
  }

  def POSTLogin() = middleware.Unauthenticated.JSON(parse.json[UserSaveDatum]) { (datum) => implicit req =>
    for {
      userOpt <- db.exec(FetchUserOpt(datum.device_uuid))

      user <- userOpt match {
        case Some(u) =>
          db.exec(UpdateUser(u.user_id, datum))
          db.exec(FetchUser(u.user_id))

        case None =>
          db.exec(CreateNewUser(datum))
      }

    } yield Json.obj("user" -> user)
  }
}
