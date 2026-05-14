package saferis.tests

import saferis.*
import saferis.postgres.given
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.test.*
import zio.test.Assertion.*

object TimeoutSpecs extends ZIOSpecDefault:

  // pg_sleep(n) blocks server-side for n seconds — perfect for forcing a timeout.
  private val sleep5 = sql"select pg_sleep(5)".queryValue[Int]
  private val fast   = sql"select 1".queryValue[Int]

  private val noTimeoutLayer: ULayer[Transactor] =
    DataSourceProvider.default >>> Transactor.layer()

  private val defaultTimeoutLayer: ULayer[Transactor] =
    DataSourceProvider.default >>> Transactor.layer(defaultTimeout = Some(1.second))

  private val tenSecondDefaultLayer: ULayer[Transactor] =
    DataSourceProvider.default >>> Transactor.layer(defaultTimeout = Some(10.seconds))

  private val pureUnitTests = suite("Saferis.toJdbcSeconds")(
    test("rounds sub-second up to 1"):
      assertTrue(Saferis.toJdbcSeconds(100.millis) == 1)
    ,
    test("treats zero as 1 second"):
      assertTrue(Saferis.toJdbcSeconds(Duration.Zero) == 1)
    ,
    test("treats negative as 1 second"):
      assertTrue(Saferis.toJdbcSeconds(-5.seconds) == 1)
    ,
    test("rounds up partial seconds"):
      assertTrue(Saferis.toJdbcSeconds(1500.millis) == 2)
    ,
    test("preserves whole seconds"):
      assertTrue(Saferis.toJdbcSeconds(5.seconds) == 5)
    ,
    test("clamps to Int.MaxValue"):
      assertTrue(Saferis.toJdbcSeconds(Duration.Infinity) == Int.MaxValue),
  )

  private val aspectTests = suite("Saferis.queryTimeout aspect")(
    test("times out a slow query"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(sleep5).exit @@ Saferis.queryTimeout(1.second)
      yield assert(result)(fails(isSubtype[SaferisError.Timeout](anything)))
    ,
    test("does not affect fast queries"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(fast) @@ Saferis.queryTimeout(5.seconds)
      yield assertTrue(result.contains(1))
    ,
    test("scope is bounded — outer query is unaffected"):
      // Inner aspect-wrapped slow query times out; outer unwrapped slow query would succeed,
      // but to keep the test fast we just confirm the outer fast query runs unaffected
      // and the inner times out.
      for
        xa          <- ZIO.service[Transactor]
        innerResult <- xa.run(sleep5).exit @@ Saferis.queryTimeout(1.second)
        outerResult <- xa.run(fast)
      yield assert(innerResult)(fails(isSubtype[SaferisError.Timeout](anything))) &&
        assertTrue(outerResult.contains(1)),
  ).provideShared(noTimeoutLayer)

  private val transactorDefaultTests = suite("Transactor defaultTimeout")(
    test("times out a slow query via run"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(sleep5).exit
      yield assert(result)(fails(isSubtype[SaferisError.Timeout](anything)))
    ,
    test("times out a slow query via transact (FromConnection forwards default)"):
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.transact(sleep5).exit
      yield assert(result)(fails(isSubtype[SaferisError.Timeout](anything))),
  ).provideShared(defaultTimeoutLayer)

  private val precedenceTests = suite("precedence")(
    test("aspect overrides Transactor default (aspect wins with shorter timeout)"):
      // Default 10s would let pg_sleep(5) succeed; aspect 1s should make it fail.
      for
        xa     <- ZIO.service[Transactor]
        result <- xa.run(sleep5).exit @@ Saferis.queryTimeout(1.second)
      yield assert(result)(fails(isSubtype[SaferisError.Timeout](anything)))
  ).provideShared(tenSecondDefaultLayer)

  override def spec = suite("Statement timeouts")(
    pureUnitTests,
    aspectTests,
    transactorDefaultTests,
    precedenceTests,
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

end TimeoutSpecs
