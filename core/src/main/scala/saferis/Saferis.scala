package saferis

import zio.*

/** Package-level utilities for Saferis.
  *
  * Currently exposes the [[queryTimeout]] aspect, which scopes a JDBC statement timeout to any Saferis fragments
  * executed inside the decorated effect.
  */
object Saferis:

  /** FiberRef holding the aspect-set query timeout. Read by `SqlFragment` at execution time; written via
    * `Saferis.queryTimeout`'s [[ZIOAspect]].
    */
  private[saferis] val timeoutFiberRef: FiberRef[Option[Duration]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make[Option[Duration]](None))

  /** ZIO aspect that bounds every Saferis query inside the decorated effect by `d`.
    *
    * Resolution order at execution time (highest priority first):
    *   1. per-fragment `sql"...".withTimeout(d)`
    *   1. this aspect (`@@ Saferis.queryTimeout(d)`)
    *   1. Transactor `defaultTimeout`
    *   1. no timeout
    *
    * Composes with other ZIO aspects.
    *
    * Example:
    * {{{
    * xa.run(sql"SELECT pg_sleep(5)".queryValue[Int]) @@ Saferis.queryTimeout(1.second)
    * }}}
    */
  def queryTimeout(d: Duration): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any]:
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        timeoutFiberRef.locally(Some(d))(zio)

  /** Convert a [[Duration]] into the whole-second value JDBC's `setQueryTimeout` accepts.
    *
    * Rounds up to the nearest second and clamps to `[1, Int.MaxValue]`. The minimum-1 clamp exists because
    * `setQueryTimeout(0)` means *no limit* in JDBC, which would silently disable the cap a caller asked for.
    * Non-positive durations are treated as 1 second.
    */
  private[saferis] def toJdbcSeconds(d: Duration): Int =
    if d == Duration.Infinity then Int.MaxValue
    else
      // toSeconds and toNanos can overflow on huge finite durations; guard explicitly.
      val seconds       = d.getSeconds
      val nanosFraction = d.getNano
      if seconds <= 0L && nanosFraction <= 0 then 1
      else if seconds >= Int.MaxValue.toLong then Int.MaxValue
      else if nanosFraction > 0 then if seconds + 1L >= Int.MaxValue.toLong then Int.MaxValue else (seconds + 1L).toInt
      else seconds.toInt
  end toJdbcSeconds

end Saferis
