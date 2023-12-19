package db.schema

import db.SlickPGDriver.api._

abstract class TableLike[A](tableTag: Tag, tableName: String)extends Table[A](tableTag, tableName) {

  val Cascade = ForeignKeyAction.Cascade
  val Restrict = ForeignKeyAction.Restrict
  val SetNull = ForeignKeyAction.SetNull
  val NoAction = ForeignKeyAction.NoAction
}
