package db

import akka.actor.ActorSystem
import com.google.common.base.Throwables
import com.google.inject.ImplementedBy
import common.Config
import db.SlickPGDriver.api._
import db.schema.Users
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.concurrent.CustomExecutionContext
import task.AppError.CaughtDBException
import task.{AppError, Task}

import java.sql.SQLException
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

sealed trait QueryLike {
  type R
  type S <: NoStream
  type E <: Effect

  protected def prepare(implicit ec: DBExecutionContext): DBIOAction[R, S, E]
}

trait Query[A] extends QueryLike {
  import Query._

  type R = A
  type S = NoStream
  type E = Effect.All

  def exec(ec: DBExecutionContext)(handleSqlException: SQLException => Option[AppError]): DBIO[A] = {
    logger.trace(s"Query: ${this.toString}")

    prepare(ec)
      .map { a => logger.trace(s"Result: $a"); a }(ec)
      .cleanUp({
        case None =>
          DBIO.successful(())

        case Some(appError: AppError) =>
          logger.warn(s"Known AppError: $appError")

          DBIO.failed(appError)

        // handle known sql exceptions
        case Some(sqlException: SQLException) if handleSqlException(sqlException).nonEmpty =>
          val appError = handleSqlException(sqlException).get

          logger.warn(s"Known SQL Exception ($appError): $sqlException")

          DBIO.failed(appError)

        // override unknown db exception from db with custom AppError
        case Some(dbException) =>
          logger.error(s"Unknown DB Exception(${getClass.getName}): ${Throwables.getStackTraceAsString(dbException)}")
          // TODO: BUG: https://issues.scala-lang.org/browse/SI-2034 https://issues.scala-lang.org/browse/SI-5425
          // TODO: `SentinelQuery` object is nested so it fails - 'nested object fails when `getSimpleName` is called'
          DBIO.failed(CaughtDBException(getClass.getName, dbException))
      }, keepFailure = false)(ec)
  }
}
object Query {
  private val logger = Logger("db.DB")
}

private[db] case class UniqueIndex_UPDATE_DB(name: String, appError: AppError)
private[db] case class RestrictDeleteForeignKey_UPDATE_DB(name: String, appError: AppError)

// https://stackoverflow.com/a/53162884/3891342
@ImplementedBy(classOf[DBExecutionContextImpl])
trait DBExecutionContext extends ExecutionContext

@Singleton
class DBExecutionContextImpl @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "akka.actor.commonsense-db-dispatcher") with DBExecutionContext

@ImplementedBy(classOf[Postgres])
trait DB {
  def exec(query: QueryLike): Task[query.R]
}

@Singleton
private class Postgres @Inject() (lifecycle: ApplicationLifecycle, implicit val dbEc: DBExecutionContext) extends DB {
  import Postgres._
  import task.TaskOps._

  private val database = {
    val db = Database.forConfig(Config.CommonSenseDBConfigPath)
    lifecycle.addStopHook(() => Future.successful(db.close()))
    db
  }
  private def run[R, E <: Effect](dbio: DBIOAction[R, NoStream, E]): Task[R] = database.run(dbio).toTask(AppError.DB)

  // suppress fruitless type erasure warning. we don't access the actual result of query so there will be no runtime error.
  def exec(query: QueryLike): Task[query.R] = query match {
    case q: Query[query.R @unchecked] =>
      val dbio = q.exec(dbEc) { sqlException =>
        val errorMessage = sqlException.getMessage.replace("\n", " ")

        errorMessage match {
          case UniqueIndexConstraintRegex(indexName, columnName, duplicateValue) =>
            uniqueIndexNameToAppErrorMap.get(indexName)

          case _ =>
            None
        }
      }

      run(dbio)
  }
}

private object Postgres {
  // NOTE: There is no better way to handle these errors. This error handling is VERY specific to each implementation and may also even vary for different postgresql versions.
  // NOTE: Be very careful with these.
  val UniqueIndexConstraintRegex = """.+?duplicate key value violates unique constraint "(\w+?)"\s+?Detail: Key \((.+?)\)\=\((.+?)\) already exists.+""".r
  val ForeignKeyConstraintRegex = """.+?update or delete on table "(\w+?)" violates foreign key constraint "(\w+?)" on table "(\w+?)".+""".r

  val uniqueIndexNameToAppErrorMap = Seq(
    Users.DeviceUUIDUniqueIDX
  )
    .map(k => (k.name, k.appError))
    .toMap
}
