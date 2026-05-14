package saferis.tests

import saferis.*
import saferis.postgres.given
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.sql.SQLException

object RetryableSpecs extends ZIOSpecDefault:

  // ---- Pure unit tests for the standards-based default classifier ----

  private def sqlEx(state: String): SQLException =
    new SQLException("synthetic", state)

  private val defaultClassifierTests = suite("SaferisError.defaultRetryClassifier")(
    test("flags 08xxx connection-exception states"):
      val cls = SaferisError.defaultRetryClassifier
      assertTrue(
        cls(sqlEx("08000")),
        cls(sqlEx("08003")),
        cls(sqlEx("08006")),
        cls(sqlEx("08S01")),
      )
    ,
    test("flags 40001 serialization failure"):
      assertTrue(SaferisError.defaultRetryClassifier(sqlEx("40001")))
    ,
    test("flags 40P01 deadlock detected"):
      assertTrue(SaferisError.defaultRetryClassifier(sqlEx("40P01")))
    ,
    test("does not flag syntax errors (42xxx)"):
      assertTrue(!SaferisError.defaultRetryClassifier(sqlEx("42601")))
    ,
    test("does not flag constraint violations (23xxx)"):
      assertTrue(!SaferisError.defaultRetryClassifier(sqlEx("23505")))
    ,
    test("does not flag non-SQL throwables"):
      assertTrue(!SaferisError.defaultRetryClassifier(new RuntimeException("nope"))),
  )

  private val fromThrowableTests = suite("SaferisError.fromThrowable")(
    test("wraps in Retryable when classifier returns true"):
      val classifier: SaferisError.RetryClassifier = _ => true
      val err = SaferisError.fromThrowable(sqlEx("23505"), Some("insert ..."), classifier)
      assert(err)(isSubtype[SaferisError.Retryable](anything))
    ,
    test("classifier short-circuits SQLState categorization"):
      // Without the classifier, 23505 would be ConstraintViolation;
      // with one that flags it, we should get Retryable.
      val classifier: SaferisError.RetryClassifier =
        case e: SQLException => e.getSQLState == "23505"
        case _               => false
      val err = SaferisError.fromThrowable(sqlEx("23505"), Some("insert ..."), classifier)
      assert(err)(isSubtype[SaferisError.Retryable](anything))
    ,
    test("falls through to normal categorization when classifier returns false"):
      val err = SaferisError.fromThrowable(sqlEx("42601"), Some("bad sql"), _ => false)
      assert(err)(isSubtype[SaferisError.SyntaxError](anything))
    ,
    test("default fromThrowable (no classifier) preserves prior behavior"):
      val err = SaferisError.fromThrowable(sqlEx("23505"), Some("insert ..."))
      assert(err)(isSubtype[SaferisError.ConstraintViolation](anything)),
  )

  // ---- Integration test: user-supplied classifier wired through Transactor ----

  // Classifier that flags any SyntaxError (42xxx) as retryable. Lets us trigger Retryable
  // end-to-end with a deterministic, fast query (no need to set up a serialization conflict).
  private val syntaxIsRetryable: SaferisError.RetryClassifier =
    case e: SQLException => Option(e.getSQLState).exists(_.startsWith("42"))
    case _               => false

  private val customClassifierLayer: ULayer[Transactor] =
    DataSourceProvider.default >>> Transactor.layer(retryClassifier = Some(syntaxIsRetryable))

  private val defaultClassifierLayer: ULayer[Transactor] =
    DataSourceProvider.default >>> Transactor.layer()

  // A query that always fails with SQLState 42601 (syntax error)
  private val brokenQuery = sql"deli meat from nowhere".dml

  private val transactorWiringTests = suite("Transactor wiring")(
    test("user-supplied classifier flips a syntax error into Retryable"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(brokenQuery).exit
      yield assert(result)(fails(isSubtype[SaferisError.Retryable](anything)))
    .provideShared(customClassifierLayer),
    test("default dialect classifier leaves syntax errors as SyntaxError"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(brokenQuery).exit
      yield assert(result)(fails(isSubtype[SaferisError.SyntaxError](anything)))
    .provideShared(defaultClassifierLayer),
    test("classifier flows through transact as well"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.transact(brokenQuery).exit
      yield assert(result)(fails(isSubtype[SaferisError.Retryable](anything)))
    .provideShared(customClassifierLayer),
    test("Retryable composes with ZIO.retry"):
      // Use a classifier that flags the syntax error as retryable, then verify that
      // ZIO's retry combinator actually drives multiple attempts.
      for
        attempts <- Ref.make(0)
        xa       <- ZIO.service[Transactor]
        countingQuery = attempts.update(_ + 1) *> xa.run(brokenQuery)
        result <- countingQuery
          .retry(Schedule.recurs(2) && Schedule.recurWhile[SaferisError]:
            case _: SaferisError.Retryable => true
            case _                         => false)
          .exit
        n <- attempts.get
      yield assertTrue(n == 3) && assert(result)(fails(isSubtype[SaferisError.Retryable](anything)))
    .provideShared(customClassifierLayer),
  )

  override def spec = suite("Retryable error classification")(
    defaultClassifierTests,
    fromThrowableTests,
    transactorWiringTests,
  ) @@ TestAspect.sequential

end RetryableSpecs
