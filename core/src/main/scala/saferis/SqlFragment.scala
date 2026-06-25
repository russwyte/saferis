package saferis

import zio.*
import zio.stream.ZStream

import java.sql.PreparedStatement
import java.sql.ResultSet

type ScopedQuery[E] = ZIO[ConnectionProvider & Scope, SaferisError, E]

final case class SqlFragment(
    sql: String,
    override private[saferis] val writes: Seq[Write[?]],
    override private[saferis] val issues: List[FragmentIssue] = Nil,
) extends Placeholder:

  inline private def make[E <: Product](rs: ResultSet)(using table: Table[E])(using trace: Trace): Task[E] =
    for cs <- ZIO.foreach(table.columns)(c => c.read(rs))
    yield (Macros.make[E](cs))

  private def doWrites(statement: PreparedStatement)(using trace: Trace) = ZIO.foreachDiscard(writes.zipWithIndex):
    (write, idx) => write.write(statement, idx + 1)

  private def applyTimeout(statement: PreparedStatement)(using trace: Trace): ZIO[ConnectionProvider, Throwable, Unit] =
    for
      providerDefault <- ZIO.serviceWith[ConnectionProvider](_.defaultQueryTimeout)
      fiberRefValue   <- Saferis.timeoutFiberRef.get
      effective = fiberRefValue.orElse(providerDefault)
      _ <- effective match
        case Some(d) =>
          val seconds = Saferis.toJdbcSeconds(d)
          ZIO.attempt(statement.setQueryTimeout(seconds))
        case None => ZIO.unit
    yield ()

  /** Map a throwable to a SaferisError, consulting the active provider's `retryClassifier` so that transient failures
    * surface as `SaferisError.Retryable`.
    */
  private def classifyError[R <: ConnectionProvider, A](
      effect: ZIO[R, Throwable, A],
      sqlText: String,
  )(using trace: Trace): ZIO[R, SaferisError, A] =
    effect.flatMapError: t =>
      ZIO
        .serviceWith[ConnectionProvider](_.retryClassifier)
        .map(SaferisError.fromThrowable(t, Some(sqlText), _))

  /** Refuse to run if the fragment carries validation issues. The check happens before any connection is acquired, so
    * invalid fragments never reach JDBC.
    */
  private def validateIssues[R, A](effect: => ZIO[R, SaferisError, A])(using
      trace: Trace
  ): ZIO[R, SaferisError, A] =
    if issues.isEmpty then effect
    else ZIO.fail(SaferisError.InvalidStatement(issues))

  /** Lift this fragment's validation status into a ZIO effect.
    *
    * Succeeds with the fragment if no issues were accumulated during construction; fails with
    * [[SaferisError.InvalidStatement]] otherwise. Useful for callers who want to surface or log construction failures
    * before running the statement.
    */
  def validate(using trace: Trace): IO[SaferisError, SqlFragment] =
    if issues.isEmpty then ZIO.succeed(this)
    else ZIO.fail(SaferisError.InvalidStatement(issues))

  /** Executes a query and returns an effect of a Chunk of [[Table]] instances.
    *
    * @return
    */
  inline def query[E <: Product: Table](using trace: Trace): ScopedQuery[Chunk[E]] =
    validateIssues:
      val effect = for
        connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
        statement  <- ZIO.attempt(connection.prepareStatement(sql))
        _          <- applyTimeout(statement)
        _          <- doWrites(statement)
        rs         <- ZIO.attempt(statement.executeQuery())
        results    <-
          def loop(acc: ChunkBuilder[E]): ZIO[Any, Throwable, Chunk[E]] =
            ZIO.attempt(rs.next()).flatMap { hasNext =>
              if hasNext then make[E](rs).flatMap(e => loop(acc += e))
              else ZIO.succeed(acc.result())
            }
          loop(Chunk.newBuilder[E])
      yield results
      classifyError(effect, sql)
  end query

  /** Executes a query and returns an effect of an option of [[Table]]. If the query returns no rows, None is returned.
    *
    * @return
    */
  inline def queryOne[E <: Product: Table](using trace: Trace): ScopedQuery[Option[E]] =
    validateIssues:
      val effect = for
        connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
        statement  <- ZIO.attempt(connection.prepareStatement(sql))
        _          <- applyTimeout(statement)
        _          <- doWrites(statement)
        rs         <- ZIO.attempt(statement.executeQuery())
        result     <- if rs.next() then make[E](rs).map(Some(_)) else ZIO.succeed(None)
      yield result
      classifyError(effect, sql)
  end queryOne

  /** Executes a query and returns an effect of a simple value (like Int, String, etc.) from the first column of the
    * first row. This is useful for queries like "SELECT COUNT(*)" or "SELECT MAX(age)" that return a single value. Also
    * supports tuples by validating that the result set has the expected number of columns.
    *
    * @tparam A
    *   the type to decode (must have a Decoder instance)
    * @return
    *   an effect of Option[A] - None if no results, Some(value) if results found
    */
  inline def queryValue[A](using decoder: Decoder[A])(using trace: Trace): ScopedQuery[Option[A]] =
    validateIssues:
      val effect = for
        connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
        statement  <- ZIO.attempt(connection.prepareStatement(sql))
        _          <- applyTimeout(statement)
        _          <- doWrites(statement)
        rs         <- ZIO.attempt(statement.executeQuery())
        result     <-
          if rs.next() then
            // we need to get the first column and it's name
            val name = rs.getMetaData.getColumnName(1)
            decoder.decode(rs, name).map(Some(_))
          else ZIO.succeed(None)
      yield result
      classifyError(effect, sql)
  end queryValue

  /** Executes a query and returns a lazy ZStream of [[Table]] instances.
    *
    * Unlike `query` which eagerly loads all results into a Chunk, this method streams rows lazily - ideal for large
    * result sets or real-time processing. The connection remains open until the stream is fully consumed or closed.
    *
    * @return
    *   A ZStream that lazily iterates through result rows
    */
  inline def queryStream[E <: Product: Table](using
      trace: Trace
  ): ZStream[ConnectionProvider & Scope, SaferisError, E] =
    if issues.nonEmpty then ZStream.fail(SaferisError.InvalidStatement(issues))
    else
      val thisSql    = sql
      val acquireRaw =
        for
          connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
          statement  <- ZIO.acquireRelease(ZIO.attempt(connection.prepareStatement(thisSql)))(s =>
            ZIO.succeed(s.close())
          )
          _  <- applyTimeout(statement)
          _  <- doWrites(statement)
          rs <- ZIO.acquireRelease(ZIO.attempt(statement.executeQuery()))(r => ZIO.succeed(r.close()))
        yield rs
      val acquire: ZIO[ConnectionProvider & Scope, SaferisError, ResultSet] =
        classifyError(acquireRaw, thisSql)

      def iterate(rs: ResultSet, classifier: SaferisError.RetryClassifier): ZStream[Any, SaferisError, E] =
        ZStream
          .unfoldZIO(rs): resultSet =>
            ZIO
              .attempt(resultSet.next())
              .flatMap: hasNext =>
                if hasNext then make[E](resultSet).map(e => Some((e, resultSet)))
                else ZIO.succeed(None)
          .mapError(SaferisError.fromThrowable(_, Some(thisSql), classifier))

      val streamed: ZIO[ConnectionProvider & Scope, SaferisError, ZStream[Any, SaferisError, E]] =
        for
          rs         <- acquire
          classifier <- ZIO.serviceWith[ConnectionProvider](_.retryClassifier)
        yield iterate(rs, classifier)
      ZStream.unwrapScoped[ConnectionProvider & Scope](streamed)
  end queryStream

  /** alias for [[Statement.dml]]
    *
    * @return
    */
  def update(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] = dml

  /** alias for [[Statement.dml]]
    *
    * @return
    */
  def delete(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] = dml

  /** alias for [[Statement.dml]]
    *
    * @return
    */
  def insert(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] = dml

  /** Generic execution for any SQL statement (DML or DDL). Alias for [[dml]] with a more generic name suitable for DDL
    * operations.
    * @return
    */
  def execute(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] = dml

  /** Executes the statement which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or
    * DELETE or an SQL statement that returns an Int, such as a DML statement.
    * @return
    */
  inline def dml(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    validateIssues:
      val effect = for
        connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
        statement  <- ZIO.attempt(connection.prepareStatement(sql))
        _          <- applyTimeout(statement)
        _          <- ZIO.foreach(writes.zipWithIndex): (write, idx) =>
          write.write(statement, idx + 1)
        result <- ZIO.attempt(statement.executeUpdate())
      yield result
      classifyError(effect, sql)
  end dml

  /** Strips leading whitespace from each line in the SQL string, and removes the margin character. See
    * [[String.stripMargin]]
    *
    * @param marginChar
    * @return
    */
  def stripMargin(marginChar: Char) = copy(sql = sql.stripMargin(marginChar))

  /** Strips leading whitespace from each line in the SQL string, and removes the margin character. Using the default
    * margin character '|'. See [[String.stripMargin]]
    *
    * @return
    */
  def stripMargin = copy(sql = sql.stripMargin)

  /** Appends another [[SqlFragment]] to this one.
    *
    * @param other
    * @return
    */
  def append(other: SqlFragment) =
    copy(sql = sql + other.sql, writes = writes ++ other.writes, issues = issues ++ other.issues)

  /** alias for [[append]]
    *
    * @param other
    * @return
    */
  def :+(other: SqlFragment) = append(other)

  /** Shows the SQL with parameters inlined for debugging/testing purposes if there are more parameters than '?'
    * placeholders, the extra parameters are ignored. This shouldn't happen in practice. Conversely if there are more
    * '?' placeholders than parameters, the extra placeholders are left as '?'
    *
    * @return
    */
  def show: String =
    val AverageCharsPerLiteral = 10
    val sb                     = new StringBuilder(sql.length + writes.length * AverageCharsPerLiteral)
    var idx                    = 0
    var i                      = 0
    while i < sql.length do
      val c = sql.charAt(i)
      if c == '?' && idx < writes.length then
        sb.append(writes(idx).literal)
        idx += 1
      else sb.append(c)
      i += 1
    sb.toString
  end show

end SqlFragment
