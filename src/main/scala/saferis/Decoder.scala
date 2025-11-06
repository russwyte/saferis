package saferis

import zio.*

import java.sql.ResultSet
import java.sql.SQLException

trait Decoder[A]:
  self =>

  /** Transform a Decoder[A] to Decoder[B] by mapping the value of A to Task[B]
    * @param f
    * @return
    */
  def transform[B](f: A => Task[B]): Decoder[B] =
    new Decoder[B]:
      def decode(rs: ResultSet, name: String)(using Trace): Task[B] =
        self.decode(rs, name).flatMap(f)

  /** Decode a value from the result set
    *
    * @param rs
    * @param name
    * @return
    */
  def decode(rs: ResultSet, name: String)(using Trace): Task[A]
end Decoder

object Decoder:
  given option[A: Decoder as decoder]: Decoder[Option[A]] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Option[A]] =
      ZIO
        .attempt(rs.getObject(name))
        .flatMap: a =>
          if a == null then ZIO.succeed(None)
          else decoder.decode(rs, name).map(Some(_))
  given string: Decoder[String] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[String] =
      ZIO.attempt(rs.getString(name))
  given short: Decoder[Short] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Short] =
      ZIO.attempt(rs.getShort(name))
  given int: Decoder[Int] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Int] =
      ZIO.attempt(rs.getInt(name))
  given long: Decoder[Long] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Long] =
      ZIO.attempt(rs.getLong(name))
  given boolean: Decoder[Boolean] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Boolean] =
      ZIO.attempt(rs.getBoolean(name))
  given float: Decoder[Float] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Float] =
      ZIO.attempt(rs.getFloat(name))
  given double: Decoder[Double] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Double] =
      ZIO.attempt(rs.getDouble(name))
  given date: Decoder[java.sql.Date] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.sql.Date] =
      ZIO.attempt(rs.getDate(name))
  given bigDecimal: Decoder[BigDecimal] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[BigDecimal] =
      ZIO.attempt(rs.getBigDecimal(name))
  given bigInt: Decoder[BigInt] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[BigInt] =
      ZIO.attempt(rs.getBigDecimal(name)).map(x => (x: BigDecimal).toBigInt)
  given time: Decoder[java.sql.Time] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.sql.Time] =
      ZIO.attempt(rs.getTime(name))
  given timestamp: Decoder[java.sql.Timestamp] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.sql.Timestamp] =
      ZIO.attempt(rs.getTimestamp(name))
  given url: Decoder[java.net.URL] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.net.URL] =
      ZIO.attempt(rs.getURL(name))
  given uuid: Decoder[java.util.UUID] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.util.UUID] =
      ZIO.attempt:
        val obj = rs.getObject(name)
        obj match
          case uuid: java.util.UUID => uuid
          case str: String          => java.util.UUID.fromString(str)
          case _                    => throw new SQLException(s"Cannot convert $obj to UUID")

  // Tuple decoders - decode using column indices for tuple types
  def failTupleDecode(expected: Int, actual: Int) = ZIO.fail(
    new SQLException(s"Expected exactly $expected columns in result set, got $actual")
  )
  given tuple2[A: Decoder as decoderA, B: Decoder as decoderB]: Decoder[(A, B)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B)] =
      if rs.getMetaData.getColumnCount != 2 then failTupleDecode(2, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
        yield (a, b)

  given tuple3[A: Decoder as decoderA, B: Decoder as decoderB, C: Decoder as decoderC]: Decoder[(A, B, C)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C)] =
      if rs.getMetaData.getColumnCount != 3 then failTupleDecode(3, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
        yield (a, b, c)
  end tuple3

  given tuple4[A: Decoder as decoderA, B: Decoder as decoderB, C: Decoder as decoderC, D: Decoder as decoderD]
      : Decoder[(A, B, C, D)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D)] =
      if rs.getMetaData.getColumnCount != 4 then failTupleDecode(4, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
        yield (a, b, c, d)
  end tuple4

  given tuple5[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
  ]: Decoder[(A, B, C, D, E)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E)] =
      if rs.getMetaData.getColumnCount != 5 then failTupleDecode(5, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
        yield (a, b, c, d, e)
  end tuple5

  given tuple6[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
  ]: Decoder[(A, B, C, D, E, F)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F)] =
      if rs.getMetaData.getColumnCount != 6 then failTupleDecode(6, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
        yield (a, b, c, d, e, f)
  end tuple6

  given tuple7[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
  ]: Decoder[(A, B, C, D, E, F, G)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G)] =
      if rs.getMetaData.getColumnCount != 7 then failTupleDecode(7, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
        yield (a, b, c, d, e, f, g)
  end tuple7

  given tuple8[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
  ]: Decoder[(A, B, C, D, E, F, G, H)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H)] =
      if rs.getMetaData.getColumnCount != 8 then failTupleDecode(8, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
        yield (a, b, c, d, e, f, g, h)
  end tuple8

  given tuple9[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
  ]: Decoder[(A, B, C, D, E, F, G, H, I)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I)] =
      if rs.getMetaData.getColumnCount != 9 then failTupleDecode(9, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
        yield (a, b, c, d, e, f, g, h, i)
  end tuple9

  given tuple10[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J)] =
      if rs.getMetaData.getColumnCount != 10 then failTupleDecode(10, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
        yield (a, b, c, d, e, f, g, h, i, j)
  end tuple10

  given tuple11[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K)] =
      if rs.getMetaData.getColumnCount != 11 then failTupleDecode(11, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
        yield (a, b, c, d, e, f, g, h, i, j, k)
  end tuple11

  given tuple12[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L)] =
      if rs.getMetaData.getColumnCount != 12 then failTupleDecode(12, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
        yield (a, b, c, d, e, f, g, h, i, j, k, l)
  end tuple12

  given tuple13[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
      if rs.getMetaData.getColumnCount != 13 then failTupleDecode(13, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m)
  end tuple13

  given tuple14[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
      if rs.getMetaData.getColumnCount != 14 then failTupleDecode(14, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n)
  end tuple14

  given tuple15[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
      if rs.getMetaData.getColumnCount != 15 then failTupleDecode(15, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)
  end tuple15

  given tuple16[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
      if rs.getMetaData.getColumnCount != 16 then failTupleDecode(16, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)
  end tuple16

  given tuple17[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
      if rs.getMetaData.getColumnCount != 17 then failTupleDecode(17, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)
  end tuple17

  given tuple18[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
      R: Decoder as decoderR,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
      if rs.getMetaData.getColumnCount != 18 then failTupleDecode(18, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
          r <- decoderR.decode(rs, rs.getMetaData.getColumnLabel(18))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r)
  end tuple18

  given tuple19[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
      R: Decoder as decoderR,
      S: Decoder as decoderS,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] with
    def decode(rs: ResultSet, name: String)(using
        Trace
    ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
      if rs.getMetaData.getColumnCount != 19 then failTupleDecode(19, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
          r <- decoderR.decode(rs, rs.getMetaData.getColumnLabel(18))
          s <- decoderS.decode(rs, rs.getMetaData.getColumnLabel(19))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)
  end tuple19

  given tuple20[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
      R: Decoder as decoderR,
      S: Decoder as decoderS,
      T: Decoder as decoderT,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] with
    def decode(rs: ResultSet, name: String)(using
        Trace
    ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
      if rs.getMetaData.getColumnCount != 20 then failTupleDecode(20, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
          r <- decoderR.decode(rs, rs.getMetaData.getColumnLabel(18))
          s <- decoderS.decode(rs, rs.getMetaData.getColumnLabel(19))
          t <- decoderT.decode(rs, rs.getMetaData.getColumnLabel(20))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)
  end tuple20

  given tuple21[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
      R: Decoder as decoderR,
      S: Decoder as decoderS,
      T: Decoder as decoderT,
      U: Decoder as decoderU,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] with
    def decode(rs: ResultSet, name: String)(using
        Trace
    ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
      if rs.getMetaData.getColumnCount != 21 then failTupleDecode(21, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
          r <- decoderR.decode(rs, rs.getMetaData.getColumnLabel(18))
          s <- decoderS.decode(rs, rs.getMetaData.getColumnLabel(19))
          t <- decoderT.decode(rs, rs.getMetaData.getColumnLabel(20))
          u <- decoderU.decode(rs, rs.getMetaData.getColumnLabel(21))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)
  end tuple21

  given tuple22[
      A: Decoder as decoderA,
      B: Decoder as decoderB,
      C: Decoder as decoderC,
      D: Decoder as decoderD,
      E: Decoder as decoderE,
      F: Decoder as decoderF,
      G: Decoder as decoderG,
      H: Decoder as decoderH,
      I: Decoder as decoderI,
      J: Decoder as decoderJ,
      K: Decoder as decoderK,
      L: Decoder as decoderL,
      M: Decoder as decoderM,
      N: Decoder as decoderN,
      O: Decoder as decoderO,
      P: Decoder as decoderP,
      Q: Decoder as decoderQ,
      R: Decoder as decoderR,
      S: Decoder as decoderS,
      T: Decoder as decoderT,
      U: Decoder as decoderU,
      V: Decoder as decoderV,
  ]: Decoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] with
    def decode(rs: ResultSet, name: String)(using
        Trace
    ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
      if rs.getMetaData.getColumnCount != 22 then failTupleDecode(22, rs.getMetaData.getColumnCount)
      else
        for
          a <- decoderA.decode(rs, rs.getMetaData.getColumnLabel(1))
          b <- decoderB.decode(rs, rs.getMetaData.getColumnLabel(2))
          c <- decoderC.decode(rs, rs.getMetaData.getColumnLabel(3))
          d <- decoderD.decode(rs, rs.getMetaData.getColumnLabel(4))
          e <- decoderE.decode(rs, rs.getMetaData.getColumnLabel(5))
          f <- decoderF.decode(rs, rs.getMetaData.getColumnLabel(6))
          g <- decoderG.decode(rs, rs.getMetaData.getColumnLabel(7))
          h <- decoderH.decode(rs, rs.getMetaData.getColumnLabel(8))
          i <- decoderI.decode(rs, rs.getMetaData.getColumnLabel(9))
          j <- decoderJ.decode(rs, rs.getMetaData.getColumnLabel(10))
          k <- decoderK.decode(rs, rs.getMetaData.getColumnLabel(11))
          l <- decoderL.decode(rs, rs.getMetaData.getColumnLabel(12))
          m <- decoderM.decode(rs, rs.getMetaData.getColumnLabel(13))
          n <- decoderN.decode(rs, rs.getMetaData.getColumnLabel(14))
          o <- decoderO.decode(rs, rs.getMetaData.getColumnLabel(15))
          p <- decoderP.decode(rs, rs.getMetaData.getColumnLabel(16))
          q <- decoderQ.decode(rs, rs.getMetaData.getColumnLabel(17))
          r <- decoderR.decode(rs, rs.getMetaData.getColumnLabel(18))
          s <- decoderS.decode(rs, rs.getMetaData.getColumnLabel(19))
          t <- decoderT.decode(rs, rs.getMetaData.getColumnLabel(20))
          u <- decoderU.decode(rs, rs.getMetaData.getColumnLabel(21))
          v <- decoderV.decode(rs, rs.getMetaData.getColumnLabel(22))
        yield (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)
  end tuple22

end Decoder
