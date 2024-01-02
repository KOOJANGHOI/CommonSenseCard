package controllers.api.user

import db.query.api.saveDatum.UserSaveDatum
import controllers.{ControllerLike, ControllerParams}
import db.query.api.user.{FetchUserOpt, UpdateRating, CreateNewUser, UpdateUser, FetchUser}
import play.api.libs.json.Json

import javax.inject.Inject

case class PATCHRatingBody(item_id: Int, rating: Int)
object PATCHRatingBody { implicit val reads = Json.reads[PATCHRatingBody] }

class UserController @Inject() (protected val cp: ControllerParams) extends ControllerLike {
  def POSTLogin() = middleware.Unauthenticated.JSON(parse.json[UserSaveDatum]) { (datum) =>_ =>
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

  // TODO(impl): Remove `user_id` after testing authenticateRequest. user_id can be inferred
  def PATCHRating(user_id: Int) = middleware.Authenticated.User.JSON()(parse.json[PATCHRatingBody]) { case (me, body) => _ =>
    for {
      updatedRating <- db.exec(UpdateRating(user_id, body))
    } yield Json.obj("rating" -> updatedRating)
  }
}
