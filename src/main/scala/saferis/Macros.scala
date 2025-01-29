package saferis

import java.sql.SQLException
import scala.annotation.experimental
import scala.quoted.*

object Macros:

  private[saferis] inline def nameOf[A]: String = ${ nameOfImpl[A] }

  private def nameOfImpl[A: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A]
    tpe.typeSymbol.annotations
      .collectFirst { case Apply(Select(New(TypeIdent("tableName")), _), List(Literal(StringConstant(name)))) =>
        Expr(name)
      }
      .getOrElse(Expr(tpe.typeSymbol.name))

  private[saferis] inline def columnsOf[A <: Product]: Seq[Column[?]] = ${ columnsOfImpl[A] }

  private def columnsOfImpl[A: Type](using Quotes): Expr[Seq[Column[?]]] =
    import quotes.reflect.*
    val tpe    = TypeRepr.of[A]
    val fields = tpe.typeSymbol.caseFields
    val columns = fields.map { field =>
      val fieldName = field.name
      field.tree match
        case valDef: ValDef =>
          valDef.tpt.tpe.asType match
            case '[a] =>
              val reader = summonReader[a]
              '{
                val label = ${ getLabel[A](fieldName) }
                Column[a](${ Expr(fieldName) }, label)(using $reader)
              }
      end match
    }
    Expr.ofSeq(columns)
  end columnsOfImpl

  private[saferis] transparent inline def metadataOf[A <: Product]                = ${ metadataOfImpl[A]('None) }
  private transparent inline def metadataOf2[A <: Product](alias: Option[String]) = ${ metadataOfImpl[A]('alias) }
  private[saferis] transparent inline def metadataOf[A <: Product](alias: String) = metadataOf2[A](Some(alias))

  private def metadataOfImpl[A: Type](alias: Expr[Option[String]])(using Quotes) =
    import quotes.reflect.*
    val columns = columnsOfImpl[A]
    val name    = nameOfImpl[A]
    val tpe     = TypeRepr.of[A]
    val fields  = tpe.typeSymbol.caseFields
    val names   = fields.map(_.name)
    val nameMap = '{ ${ columns }.map(c => c.name -> c.label).toMap }
    val refined = refinement(names)

    val res = refined.asType match
      case '[t] =>
        '{
          new Metadata(
            $name,
            $nameMap,
            $alias,
          ).asInstanceOf[t]
        }
    res
  end metadataOfImpl

  private def getLabel[T: Type](elemName: String)(using
      Quotes
  ): Expr[String] =
    import quotes.reflect.*
    val a = TypeRepr.of[label].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .map(term => term.asExprOf[label])
      .map(label => '{ $label.name })
      .getOrElse(Expr(elemName))
  end getLabel

  private def summonReader[T: Type](using Quotes): Expr[Reader[T]] =
    import quotes.reflect.*
    Expr
      .summon[Reader[T]]
      .orElse(
        TypeRepr.of[T].widen.asType match
          case '[tpe] =>
            Expr
              .summon[Reader[tpe]]
              .map(codec => '{ $codec.asInstanceOf[Reader[T]] })
      )
      .getOrElse:
        report.errorAndAbort(s"Could not find a Reader instance for ${Type.show[T]}")
  end summonReader

  private[saferis] inline def make[A](args: Seq[(String, Any)]): A = ${ makeImpl[A]('args) }

  private def makeImpl[A: Type](args: Expr[Seq[(String, Any)]])(using Quotes): Expr[A] =
    import quotes.reflect.*

    val tpe         = TypeRepr.of[A]
    val companion   = tpe.typeSymbol.companionModule
    val applyMethod = companion.methodMember("apply").head
    val fields      = tpe.typeSymbol.caseFields
    val typeName    = Type.show[A]

    val argsMap = '{ $args.toMap }

    val argsExprs = fields.map { param =>
      val paramName = param.name
      val paramType = param.info
      argsMap match
        case '{ $mapExpr: Map[String, Any] } =>
          paramType.asType match
            case '[t] =>
              '{
                val map = $mapExpr
                map
                  .get(${ Expr(paramName) })
                  .map(x => x.asInstanceOf[t])
                  .getOrElse:
                    throw new SQLException(
                      s"Error constructing instance of ${${ Expr(typeName) }}. Could not find value for parameter ${${ Expr(paramName) }}"
                    )
              }
      end match
    }

    Apply(Select(Ref(companion), applyMethod), argsExprs.map(_.asTerm).toList).asExprOf[A]
  end makeImpl

  // This method is used to refine the Metadata type with the field names
  // Metadata is a structural type
  private def refinement(fieldNames: Seq[String])(using Quotes) =
    import quotes.reflect.*
    fieldNames.foldLeft(TypeRepr.of[Metadata])((t, n) => Refinement(t, n, TypeRepr.of[RawSql]))

end Macros
