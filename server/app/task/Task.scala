package task

import scalaz._

import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration


/**
 * Monadic data structure that abstracts over scalaz's `Future` and scalaz's `\/` with typed error.
 *
 * Note that internally, scalaz's Future is used for various reasons:
 * 1. Doesn't handle error - `Exception`s are explicitly thrown, which makes simpler to come with custom error handling logic.
 * 2. Deterministic - `Future` is a trampolined computation, which means it runs when you explicitly tell it to `run`.
 * 3. Efficient - by the same token, since it is a trampolined computation, it doesn't submit to a new thread on `map` or `flatMap` operations. It simply runs on the current thread (this is because the operation is 'described' before being explicitly 'run'.)
 *
 * However, since scalaz's `Future` is incompatible with scala's `Future`, scala's `Future` will only ever be exposed to users through `from` and `run`. The use of scalaz's `Future` is strictly an implementation detail.
 *
 * NOTE:
 * There is a conceptual mismatch between `bimap` (`on`) and `flatMap` because one class should not have access to both but we made `Task` special by hard-coding left type to be `AppError`.
 * So what this implies is that the following code is subtly **illegal** because `bimap` never runs albeit it being binary functor map:
 * {{{ Task.error(???).flatMap(_ => Task.just(???).bimap(???, ???)) }}}
 */
class Task[A] private[task] (private val futureBox: Task.FutureBox[A]) {
  import Task._

  /**
   * NOTE: Task cannot be an instance of Bifunctor since Bifunctor requires two type parameters. Be careful with this. Refer to NOTE above.
   */
  private def bimap[B](f: E => E, g: A => B): Task[B] = {
    val fb = futureBox.map[Box[B]] {
      case -\/(e) => -\/(f(e))
      case \/-(a) => tryRun(g(a))
    }

    new Task(fb)
  }

  // catch exception if `f` throws
  def map[B](f: A => B): Task[B] = bimap(identity, f)

  // doesn't catch exception if `f` throws
  def mapError[F <: E](pf: PartialFunction[E, F]): Task[A] = bimap(e => if (pf.isDefinedAt(e)) pf(e) else e, identity)

  def flatMap[B](f: A => Task[B]): Task[B] = {
    val fb = futureBox.flatMap {
      case left@ -\/(_) => ScalazFuture.now(left)
      case \/-(a) => tryRun(f(a)) match {
        case left@ -\/(_) => ScalazFuture.now(left)
        case \/-(b) => b.futureBox
      }
    }
    new Task(fb)
  }

  /**
   * Insert side-effecting operation
   */
  def on(onError: E => Unit, onSuccess: A => Unit): Task[A] =
    bimap(e => { onError(e); e }, a => { onSuccess(a); a })

  def onError(f: E => Unit): Task[A] =
    on(f, a => ())

  def onSuccess(f: A => Unit): Task[A] =
    on(e => (), f)

  /**
   * Used like `finally`. Close used resource etc.
   */
  def onFinish(f: => Unit): Task[A] =
    on(_ => f, _ => f)

  /**
   * HACK: workaround for http://stackoverflow.com/questions/4380831/why-does-filter-have-to-be-defined-for-pattern-matching-in-a-for-loop-in-scala
   * This should never be called by code. It is exclusively used for for comprehension tuple unapply bug.
   */
  def withFilter(f: A => Boolean): Task[A] = flatMap(a => if (f(a)) Task.just(a) else Task.error(task.AppError.TaskFilter))

  /**
   * Materialize put-aside error into being and use Either[E, A] for type signature instead.
   * Task becomes error-free (except for system errors - which should not be handled by programmer anyway)
   * and user can opt to handle materialized error in custom way.
   */
  def materializeError(): Task[Either[E, A]] = {
    val fe: FutureBox[Either[E, A]] = futureBox.map {
      case -\/(e) => \/-(Left(e))
      case \/-(a) => \/-(Right(a))
    }
    new Task(fe)
  }

  /**
   * Similar to `materializeError` but accept a partial function to catch and transform `E` into `A`.
   * Unhandled `E` will remain as `E`.
   * Unlike `materializeError`, calling this method doesn't guarantee an error-free `Task`
   */
  def catchError(f: PartialFunction[E, A]): Task[A] = {
    val fa = futureBox.map {
      case -\/(e) if f.isDefinedAt(e) => \/-(f(e))
      case left @ -\/(_) => left
      case right @ \/-(_) => right
    }
    new Task(fa)
  }

  def retryIf(maxRetries: Int)(retryIf: PartialFunction[(E, Int), Task[Unit]]): Task[A] = {
    def go(currentRetry: Int): FutureBox[A] = {
      futureBox.flatMap {
        case -\/(e) if retryIf.isDefinedAt((e, currentRetry)) =>
          if (currentRetry > maxRetries) {
            ScalazFuture.now(-\/(e))

          } else {
            for {
              _ <- retryIf((e, currentRetry)).futureBox
              a <- go(currentRetry + 1)
            } yield a
          }

        case left @ -\/(_) =>
          ScalazFuture.now(left)

        case right @ \/-(_) =>
          ScalazFuture.now(right)
      }
    }

    val fa = go(1)

    new Task(fa)
  }

  /**
   * Delay the execution of this Task by `t`
   */
  def after(t: Duration): Task[A] = {
    val fa = futureBox.after(t)
    new Task(fa)
  }

  /**
   * Run underlying `scalaz.concurrent.Future[A]` and fold it to `scala.concurrent.Future[B]`
   */
  def run[B](failed: E => B, succeeded: A => B): ScalaFuture[B] = {
    val p = scala.concurrent.Promise[B]()

    futureBox.unsafePerformAsync {
      case -\/(e) => p.success(failed(e))
      case \/-(a) => p.success(succeeded(a))
    }

    p.future
  }

  /**
   * Convert underlying `scalaz.concurrent.Future[A]` to `scala.concurrent.Future[A]` without handling error
   */
  def toFuture: ScalaFuture[A] = {
    val p = scala.concurrent.Promise[A]()

    futureBox.unsafePerformAsync {
      case -\/(e) => p.failure(e)
      case \/-(a) => p.success(a)
    }

    p.future
  }
}

object Task {
  /**
   * `AppError` is enforced as error type.
   */
  type E = AppError
  private[task] type Box[A] = E \/ A
  private[task] type FutureBox[A] = ScalazFuture[E \/ A]

  type ScalazFuture[A] = scalaz.concurrent.Future[A]
  val ScalazFuture = scalaz.concurrent.Future

  type ScalaFuture[A] = scala.concurrent.Future[A]
  type NettyFuture[A] = io.netty.util.concurrent.Future[A]

  /**
   * Wrap Box[A] (E \/ A) into Task[A]
   */
  def wrap[A](box: => Box[A]): Task[A] = new Task(ScalazFuture.delay(box))

  /**
   * `unit` operation on passed value with success.
   */
  def just[A](a: => A): Task[A] = wrap(tryRun(a))

  /**
   * `unit` operation with `Unit`
   */
  def empty: Task[Unit] = just(())

  /**
   * `unit` operation on passed error with failure
   */
  def error[A](e: E): Task[A] = wrap(-\/(e))

  /**
   * return `Unit` if `predicate` is true otherwise return `otherwise`
   */
  def guard(predicate: => Boolean, otherwise: => E): Task[Unit] =
    if (predicate) Task.empty
    else Task.error(otherwise)

  /**
   *
   */
  def runIf[A](predicate: => Boolean)(f: => Task[Unit]): Task[Unit] =
    if (predicate) f
    else Task.empty

  /**
   * Run potentially error-throwing block in future context and convert to safe, error-typed `Task`.
   *
   * Need to supply function to handle error from block execution
   */
  def apply[A](a: => A)(f: Throwable => E): Task[A] = {
    val fa = ScalazFuture { tryRun[A](a, f) }
    new Task(fa)
  }

  /**
   * Run potentially error-throwing async operation in future context and convert to safe, error-typed `Task`
   */
  def async[A](asyncF: (E => Unit, A => Unit) => Unit)(f: Throwable => E): Task[A] = {
    val fa = ScalazFuture.async[Box[A]] { cb =>
      try {
        asyncF(
          error => {
            cb(-\/(error))
          },
          a => {
            cb(\/-(a))
          }
        )
      } catch {
        case e: E => cb(-\/(e))
        case scala.util.control.NonFatal(nonFatalError) => cb(-\/(f(nonFatalError)))
      }
    }

    new Task[A](fa)
  }

  /**
   * Utility function to execute a block and wrap it in `Box[A]`
   */
  private def tryRun[A](a: => A, f: Throwable => E = AppError.CaughtNonFatalException): Box[A] =
    try \/-(a)
    catch {
      case e: E => -\/(e)
      case scala.util.control.NonFatal(error) => -\/(f(error)) // NOTE: Must not catch fatal errors.
    }

  /* Instances */
  // Referred to `taskInstance` implementation in `scalaz.concurrent.Task`
  // Nondeterminism extends Monad
  implicit val nondeterminism = new Nondeterminism[Task] {
    private val F = Nondeterminism[ScalazFuture]

    def chooseAny[A](head: Task[A], tail: Seq[Task[A]]): Task[(A, Seq[Task[A]])] = {
      val farb = F.chooseAny(head.futureBox, tail.map(_.futureBox))
      val fax = F.map(farb) { case (a, residuals) => a.map(a => (a, residuals.map(fa => new Task(fa)))) }
      new Task(fax)
    }

    def point[A](a: => A) = Task.just(a)
    def bind[A, B](fa: Task[A])(f: A => Task[B]) = fa.flatMap(f)
  }
}

trait TaskOps {
  import AppError.JsonParseException
  import Task._
  import play.api.libs.json._

  import scala.util.{Failure, Success}

  implicit class OptionOps[A](option: Option[A]) {
    def toTask(e: => E) = option.fold[Task[A]](Task.error(e))(a => Task.just(a))
  }

  implicit class ScalaFutureOps[A](scalaFuture: ScalaFuture[A]) {
    def toTask(errorHandler: Throwable => E)(implicit ec: ExecutionContext): Task[A] = {
      val fa = ScalazFuture.async[Box[A]] { cb =>
        scalaFuture.onComplete {
          case Success(a) => cb(\/-(a))
          case Failure(e) => e match {
            case e: E => cb(-\/(e))
            case scala.util.control.NonFatal(error) => cb(-\/(errorHandler(error)))
          }
        }(ec)
      }

      new Task[A](fa)
    }
  }

  implicit class NettyFutureOps[A](future: NettyFuture[A]) {
    def toTask(errorHandler: Throwable => E): Task[A] = {
      val fa = ScalazFuture.async[Box[A]] { cb =>
        future.addListener((future: NettyFuture[A]) => {
          if (future.isSuccess) {
            cb(\/-(future.get))
          } else {
            future.cause match {
              case scala.util.control.NonFatal(error) => cb(-\/(errorHandler(error)))
            }
          }
        })

        ()
      }

      new Task(fa)
    }
  }

  implicit class CompletableFutureOps[A](future: CompletableFuture[A]) {
    def toTask(errorHandler: Throwable => E): Task[A] = {
      val fa = ScalazFuture.async[Box[A]] { cb =>
        future
          .thenAcceptAsync { a =>
            cb(\/-(a))
          }
          .exceptionally {
            case scala.util.control.NonFatal(error) =>
              cb(-\/(errorHandler(error)))
              return null
          }

        ()
      }

      new Task(fa)
    }
  }

  implicit class JsResultOps[A](jsResult: JsResult[A]) {
    def toTask(errorHandler: Throwable => E): Task[A] = jsResult match {
      case JsSuccess(a, _) => Task.just(a)
      case JsError(reasons) => Task.error(errorHandler(JsonParseException(reasons.map{ case (path, errors) => (path, errors.toSeq) }.toSeq)))
    }
  }

  implicit class DisjunctionOps[A](disjunction: E \/ A) {
    def toTask: Task[A] = Task.wrap(disjunction)
  }

  implicit class EitherOps[A](either: Either[E, A]) {
    def toTask: Task[A] = Task.wrap(\/.fromEither(either))
  }
}

object TaskOps extends TaskOps
