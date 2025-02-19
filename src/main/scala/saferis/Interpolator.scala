package saferis

import scala.collection.mutable as m
import scala.quoted.*

export Interpolator.sql

object Interpolator:
  extension (inline sc: StringContext)
    inline def sql(inline args: Any*): SqlFragment =
      ${ sqlImpl('sc, 'args) }

  private def sqlImpl(sc: Expr[StringContext], values: Expr[Seq[Any]])(using Quotes): Expr[SqlFragment] =
    val allArgsExprs: Seq[Expr[Any]] = values match
      case Varargs(ae) => ae
    val holder     = '{ (Vector.newBuilder[Placeholder]) }
    val placeExprs = getPlaceHoldersExpr(allArgsExprs, holder)
    val writeExprs = '{ Placeholder.allWrites($placeExprs) }
    val res = '{
      val queryStr = $sc.s($placeExprs.map(_.sql)*)
      val writes   = $writeExprs
      SqlFragment(sql = queryStr, writes = writes)
    }
    res

  end sqlImpl

  type HoldersBuilder = m.Builder[Placeholder, Vector[Placeholder]]

  private def getPlaceHoldersExpr(all: Seq[Expr[Any]], builder: Expr[HoldersBuilder])(using
      Quotes
  ): Expr[Vector[Placeholder]] =
    all match
      case '{ $arg: Placeholder } +: rest =>
        val acc = '{ $builder.addOne($arg) }
        getPlaceHoldersExpr(rest, acc)
      case '{ $arg: tp } +: rest =>
        val ph  = summonPlaceholder[tp](arg)
        val acc = '{ $builder.addOne($ph) }
        getPlaceHoldersExpr(rest, acc)
      case _ =>
        '{ $builder.result() }
    end match
  end getPlaceHoldersExpr

  private def summonPlaceholder[T: Type](arg: Expr[T])(using Quotes): Expr[Placeholder] =
    '{
      val e = ${ Macros.summonEncoder[T] }
      Placeholder(${ arg })(using e)
    }
end Interpolator
