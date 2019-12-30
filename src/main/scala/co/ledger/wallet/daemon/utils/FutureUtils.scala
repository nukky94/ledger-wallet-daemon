package co.ledger.wallet.daemon.utils

import java.util.{Timer, TimerTask}

import com.twitter.util.logging.Logging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}

object FutureUtils extends Logging {

  // All Future's that use futureWithTimeout will use the same Timer object
  // it is thread safe and scales to thousands of active timers
  // The true parameter ensures that timeout timers are daemon threads and do not stop
  // the program from shutting down
  private val timer: Timer = new Timer(true)

  /**
    * Returns the result of the provided future within the given time or a timeout exception, whichever is first
    * This uses Java Timer which runs a single thread to handle all futureWithTimeouts and does not block like a
    * Thread.sleep / Await.result would
    * @param future Caller passes a future to execute
    * @param timeout Time before we return a Timeout exception instead of future's outcome
    * @param onError Error handler
    * @return Future[T]
    */
  def withTimeout[T](future : Future[T], timeout : FiniteDuration, onError: => Unit = ())(implicit ec: ExecutionContext): Future[T] = {
    // Promise will be fulfilled with either the callers Future or the timer task if it times out
    val p = Promise[T]

    // and a Timer task to handle timing out
    val timerTask = new TimerTask() {
      def run() : Unit = {
        p.tryFailure(new TimeoutException())
        onError
      }
    }

    val (cancelFuture, f1) = cancellable(future)(onError)

    // Set the timeout to check in the future
    timer.schedule(timerTask, timeout.toMillis)

    f1.map { a =>
      if (p.trySuccess(a)) {
        timerTask.cancel()
      }
    }
    .recover {
      case e: Exception =>
        if (p.tryFailure(e)) {
          timerTask.cancel()
          cancelFuture()
        }
    }

    p.future
  }

  /**
    * Scala Future doesn't provide a cancel function.
    * As a workaround, you could use the firstCompletedOf to wrap 2 futures.
    * The future you want to cancel and a future that comes from a custom Promise.
    * You could then cancel the thus created future by failing the promise.
    * @param future Caller passes a future to execute
    * @param onError Error handler
    * @return Future[T]
    */
  def cancellable[T](future: Future[T])(onError: => Unit)(implicit ec: ExecutionContext): (() => Unit, Future[T]) = {
    val p = Promise[T]
    val first = Future.firstCompletedOf(Seq(p.future, future))
    val cancellation: () => Unit = {
      () =>
        first.failed.foreach(_ => onError)
        p.failure(new Exception)
    }
    (cancellation, first)
  }
}
