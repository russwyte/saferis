package saferis

import zio.*
import zio.stream.ZStream

import java.sql.PreparedStatement
import java.sql.ResultSet

type ScopedQuery[E] = ZIO[ConnectionProvider & Scope, SaferisError, E]

final case class SqlFragment(
    sql: String,
    override private[saferis] val writes: Seq[Write[?]],
) extends Placeholder:

  inline private def make[E <: Product](rs: ResultSet)(using table: Table[E])(using trace: Trace): Task[E] =
    for cs <- ZIO.foreach(table.columns)(c => c.read(rs))
    yield (Macros.make[E](cs))
  end make

  private def doWrites(statement: PreparedStatement)(using trace: Trace) = ZIO.foreachDiscard(writes.zipWithIndex):
    (write, idx) => write.write(statement, idx + 1)

  /** Executes a query and returns an effect of a Chunk of [[Table]] instances.
    *
    * @return
    */
  inline def query[E <: Product: Table](using trace: Trace): ScopedQuery[Chunk[E]] =
    val effect = for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
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
    effect.mapError(SaferisError.fromThrowable(_, Some(sql)))
  end query

  /** Executes a query and returns an effect of an option of [[Table]]. If the query returns no rows, None is returned.
    *
    * @return
    */
  inline def queryOne[E <: Product: Table](using trace: Trace): ScopedQuery[Option[E]] =
    val effect = for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _          <- doWrites(statement)
      rs         <- ZIO.attempt(statement.executeQuery())
      result     <- if rs.next() then make[E](rs).map(Some(_)) else ZIO.succeed(None)
    yield result
    effect.mapError(SaferisError.fromThrowable(_, Some(sql)))
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
    val effect = for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _          <- doWrites(statement)
      rs         <- ZIO.attempt(statement.executeQuery())
      result     <-
        if rs.next() then
          // we need to get the first column and it's name
          val name = rs.getMetaData.getColumnName(1)
          decoder.decode(rs, name).map(Some(_))
        else ZIO.succeed(None)
    yield result
    effect.mapError(SaferisError.fromThrowable(_, Some(sql)))
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
    val thisSql                                                           = sql
    val acquire: ZIO[ConnectionProvider & Scope, SaferisError, ResultSet] =
      (for
        connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
        statement  <- ZIO.acquireRelease(ZIO.attempt(connection.prepareStatement(thisSql)))(s => ZIO.succeed(s.close()))
        _          <- doWrites(statement)
        rs         <- ZIO.acquireRelease(ZIO.attempt(statement.executeQuery()))(r => ZIO.succeed(r.close()))
      yield rs).mapError(SaferisError.fromThrowable(_, Some(thisSql)))

    def iterate(rs: ResultSet): ZStream[Any, SaferisError, E] =
      ZStream
        .unfoldZIO(rs): resultSet =>
          ZIO
            .attempt(resultSet.next())
            .flatMap: hasNext =>
              if hasNext then make[E](resultSet).map(e => Some((e, resultSet)))
              else ZIO.succeed(None)
        .mapError(SaferisError.fromThrowable(_, Some(thisSql)))

    ZStream.unwrapScoped[ConnectionProvider & Scope](acquire.map(rs => iterate(rs)))
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
    val effect = for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _          <- ZIO.foreach(writes.zipWithIndex): (write, idx) =>
        write.write(statement, idx + 1)
      result <- ZIO.attempt(statement.executeUpdate())
    yield result
    effect.mapError(SaferisError.fromThrowable(_, Some(sql)))

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
  def append(other: SqlFragment) = copy(sql = sql + other.sql, writes = writes ++ other.writes)

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
