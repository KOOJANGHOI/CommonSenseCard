package controllers.app

import controllers.{ControllerLike, ControllerParams}
import db.query.api.item.FetchItem

import javax.inject.Inject

class AppController @Inject() (protected val cp: ControllerParams) extends ControllerLike {
  // TODO(simon): done test on 2023/12/19
  def GET() = middleware.Unauthenticated() { (_) => implicit req =>
    for {
      result <- {
        for {
          item <- db.exec(FetchItem(2))
        } yield Ok(views.html.index(item.contents))
      }
    } yield result
  }
}
