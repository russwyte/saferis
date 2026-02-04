package saferis

import scala.collection.mutable as m
import scala.quoted.*
import scala.annotation.Annotation

class silent extends Annotation

export Interpolator.sql
export Interpolator.sqlEcho

object Interpolator:
  extension (inline sc: StringContext)
    /** Interpolates a string context creating an [[SqlFragment]].
      *
      * @param args
      * @return
      */
    inline def sql(inline args: Any*): SqlFragment =
      ${ sqlImpl('sc, 'args, '{ false }) }

    /** Interpolates a string context creating an [[SqlFragment]] and reports the statement SQL as compilation info.
      *
      * @param args
      * @return
      */
    inline def sqlEcho(inline args: Any*): SqlFragment =
      ${ sqlImpl('sc, 'args, '{ true }) }
  end extension

  private def sqlImpl(sc: Expr[StringContext], values: Expr[Seq[Any]], verbose: Expr[Boolean])(using
      Quotes
  ): Expr[SqlFragment] =
    import quotes.reflect.*
    val isSilent = !verbose.valueOrAbort

    val allArgsExprs: Seq[Expr[Any]] = values match
      case Varargs(ae) => ae

    if !isSilent then
      val parts: Seq[String] = sc match
        case '{ StringContext(${ Varargs(partsExpr) }*) } =>
          partsExpr.map { case '{ $part: String } =>
            part.asTerm match
              case Literal(StringConstant(str)) => str
              case _                            =>
                report.errorAndAbort("StringContext parts must be string literals")
          }
        case _ =>
          report.errorAndAbort("Expected a literal StringContext")

      val argumentDisplayStrings = allArgsExprs.map(argDisplayString)
      val sqlStatement           =
        if parts.size == 1 && argumentDisplayStrings.isEmpty then parts.head
        else
          val sb = new StringBuilder(parts.head)
          for (part, repr) <- parts.tail.zip(argumentDisplayStrings) do sb.append(repr).append(part)
          sb.toString.stripMargin

      report.info(s"Generated SQL: $sqlStatement", Position.ofMacroExpansion)
    end if

    val holder     = '{ (Vector.newBuilder[Placeholder]) }
    val placeExprs = getPlaceHoldersExpr(allArgsExprs, holder)
    val writeExprs = '{ Placeholder.allWrites($placeExprs) }
    val query      = '{ $sc.s($placeExprs.map(_.sql)*) }
    val res        = '{
      SqlFragment($query, $writeExprs)
    }
    res

  end sqlImpl

  type HoldersBuilder = m.Builder[Placeholder, Vector[Placeholder]]

  private def getPlaceHoldersExpr(all: Seq[Expr[Any]], builder: Expr[HoldersBuilder])(using
      Quotes
  ): Expr[Vector[Placeholder]] =
    all match
      // Special handling for Instance - compute SQL from internal fields, not .sql method
      // This avoids collision when Instance has a field named "sql"
      case '{ $arg: Instance[a] } +: rest =>
        val ph = '{
          val inst = $arg
          // Compute table SQL directly from internal fields
          Placeholder.raw(inst.alias.fold(inst.tableName)(a => s"${inst.tableName} as ${a.value}"))
        }
        val acc = '{ $builder.addOne($ph) }
        getPlaceHoldersExpr(rest, acc)
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

  private def argDisplayString(expr: Expr[Any])(using Quotes): String =
    import quotes.reflect.*
    expr.asTerm match
      case Literal(const) =>
        const.value match
          case str: String => s"'$str'"
          case other       => other.toString
      case Select(qualifier, memberName) if qualifier.tpe <:< TypeRepr.of[saferis.Instance[?]] =>
        memberName
      case t if t.tpe <:< TypeRepr.of[saferis.Instance[?]] =>
        t.symbol.name
      case t if t.tpe <:< TypeRepr.of[saferis.SqlFragment] =>
        s"{${t.symbol.name}}"
      case _ =>
        "?"
    end match
  end argDisplayString

end Interpolator
