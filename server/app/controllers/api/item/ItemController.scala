package controllers.api.item

import controllers.{ControllerLike, ControllerParams}
import db.query.api.item.FetchItem
import db.query.api.saveDatum.ItemSaveDatum
import play.api.libs.json.Json

import javax.inject.Inject

class ItemController @Inject() (protected val cp: ControllerParams) extends ControllerLike {
  implicit val itemSaveDatumReads = ItemSaveDatum.backwardCompatibleReads

  def GET(item_id: Int) = middleware.Authenticated.User.JSON()() { (me, _) => _ =>
    for {
      item <- db.exec(FetchItem(item_id))
    } yield Json.obj("item" -> item)
  }
}
