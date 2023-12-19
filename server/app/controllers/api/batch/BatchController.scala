package controllers.api.batch

import common.JSON
import controllers.{ControllerLike, ControllerParams}
import db.DB
import db.query.api.item.FetchItems
import play.api.libs.json._
import task.Task

import javax.inject.Inject

case class BatchRequestBody(
                             items: Option[BatchRequestBody.ItemsBody] = None,
                           )
object BatchRequestBody {
  sealed trait ModelBodyLike

  case class ItemsBody(item_ids: Option[Seq[Int]]) extends ModelBodyLike

  implicit val reads = {
    implicit val itemsBodyReads = JSON.backwardCompatibleReads[ItemsBody] { json =>
      val body = ItemsBody(
        item_ids = json.getOpt[Seq[Int]]("item_ids"),
      )

      body
    }

    JSON.backwardCompatibleReads[BatchRequestBody] { json =>
      BatchRequestBody(
        items = json.getOpt[ItemsBody]("items")
      )
    }
  }
}

class BatchController @Inject() (protected val cp: ControllerParams) extends ControllerLike {
  def POST() = middleware.Authenticated.User.JSON()(parse.json[BatchRequestBody]) { (_, body) => _ =>
    BatchController.getFields(body)
  }
}

object BatchController {
  private def ifExists2[A <: BatchRequestBody.ModelBodyLike, O : Writes](fieldName: String, modelBodyLikeOpt: Option[A])(run: A => Task[O]): Option[Task[(String, JsValue)]] = {
    modelBodyLikeOpt.map { modelBodyLike =>
      run(modelBodyLike).map(a => (fieldName, Json.toJson(a)))
    }
  }

  def getFields(body: BatchRequestBody)(implicit db: DB): Task[JsObject] = {
    for {
      jsonFields <- Task.nondeterminism.gatherUnordered(Seq(
        ifExists2("items", body.items) { case BatchRequestBody.ItemsBody(itemIdsOpt) =>
          itemIdsOpt match {
            case Some(itemIds) =>
              db.exec(FetchItems(itemIds))

            case None =>
              db.exec(FetchItems(Seq(0)))
          }
        }
      ).flatten)

    } yield JsObject(jsonFields)
  }
}
