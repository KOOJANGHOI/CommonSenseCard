package db.query

import db.SlickPGDriver
import slick.ast.BaseTypedType
import slick.basic.BasicStreamingAction
import slick.lifted.OptionLift

trait QueryBuilderUtils extends db.TablesProvider {
  import SlickPGDriver.api._

  private[db] val AppError = task.AppError
  private[db] type AppError = task.AppError
  private[db] type DBExecutionContext = db.DBExecutionContext

  /* Implicits  */
  implicit class OptionExt[A](option: Option[A]) {
    def toDBIO(appError: AppError): DBIO[A] = option match {
      case Some(a) => DBIO.successful(a)
      case None => DBIO.failed(appError)
    }

    def forallL(p: A => Rep[Boolean]): Rep[Boolean] =
      option match {
        case Some(a) => p(a)
        case None => LiteralColumn(true)
      }

    def containsL(elem: Rep[A])(implicit ev: BaseTypedType[A]): Rep[Boolean] =
      option match {
        case Some(a) => elem === a
        case None => LiteralColumn(false)
      }
  }

  // Tables (Refer to slick.lifted.ExtensionMethodConversions:anyOptionExtensionMethods)
  implicit class RepOptionExt[A, REP_A](repOption: Rep[Option[A]])(implicit ol: OptionLift[REP_A, Rep[Option[A]]]) {
    def forall(p: REP_A => Rep[Boolean]): Rep[Boolean] =
      repOption.fold[Rep[Boolean], Rep[Boolean]](true)(a => p(a))

    def exists(p: REP_A => Rep[Boolean]): Rep[Boolean] =
      repOption.fold[Rep[Boolean], Rep[Boolean]](false)(a => p(a))

    // NOTE(danishin): Refer to #8540. Remove unnecessary null equality checks.
    def existsInSet(set: Iterable[A])(implicit ev: BaseTypedType[A]): Rep[Option[Boolean]] =
      repOption.inSet(set)

    // NOTE(danishin): Refer to #8540. Remove unnecessary null equality checks.
    def contains[B](elem: Rep[A])(implicit ev: BaseTypedType[A]): Rep[Option[Boolean]] =
      repOption === elem

    // NOTE(danishin): Refer to #9778. `NULL = NULL` is false
    def equalsNullable[B](elemOpt: Rep[Option[A]])(implicit ev: BaseTypedType[A]): Rep[Option[Boolean]] =
      repOption.fold[Rep[Option[Boolean]], Rep[Option[Boolean]]](elemOpt.isEmpty.?)(_ => repOption === elemOpt)
  }

  implicit class SeqExt[A](seq: Seq[A]) {
    def traverse[B](f: A => DBIO[B]): DBIO[Seq[B]] =
      DBIO.sequence(seq.map(f))

    def first(appError: AppError): DBIO[A] =
      seq.headOption match {
        case Some(a) => DBIO.successful(a)
        case None => DBIO.failed(appError)
      }
  }

  implicit class BasicStreamingActionExt[+R, +T, -E <: Effect](dbio: BasicStreamingAction[R, T, E]) {
    def first(appError: AppError)(implicit ec: DBExecutionContext) = {
      for {
        opt <- dbio.headOption
        t <- opt match {
          case Some(t) => DBIO.successful(t)
          case None => DBIO.failed(appError)
        }
      } yield t
    }
  }

  /* Common Functions */
  def guardDBIO(predicate: => Boolean, otherwise: => AppError): DBIO[Unit] =
    if (predicate) DBIO.successful(()) else DBIO.failed(otherwise)

  def handleField[A, B](fieldOpt: Option[A])(exec: A => DBIO[B])(implicit ec: DBExecutionContext): DBIO[Unit] = {
    fieldOpt match {
      case Some(field) => exec(field).map(_ => ())
      case None => DBIO.successful(())
    }
  }
}
