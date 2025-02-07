package saferis

import java.sql.SQLException
import scala.quoted.*
import scala.annotation.StaticAnnotation

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
                val label       = ${ getLabel[A](fieldName) }
                val isKey       = ${ elemHasAnnotation[A, saferis.key](fieldName) }
                val isGenerated = ${ elemHasAnnotation[A, saferis.generated](fieldName) }

                Column[a](${ Expr(fieldName) }, label, isKey, isGenerated, None)(using $reader)
              }
      end match
    }
    Expr.ofSeq(columns)
  end columnsOfImpl

  private[saferis] transparent inline def metadataOf[A <: Product] = ${ metadataOfImpl[A]('None) }
  private transparent inline def metadataOf2[A <: Product](alias: Option[String]) = ${
    metadataOfImpl[A]('alias)
  }
  private[saferis] transparent inline def metadataOf[A <: Product](alias: String) =
    metadataOf2[A](Some(alias))

  private def metadataOfImpl[A <: Product: Type](alias: Expr[Option[String]])(using Quotes) =
    import quotes.reflect.*
    val columns             = columnsOfImpl[A]
    val name                = nameOfImpl[A]
    val tpe                 = TypeRepr.of[A]
    val caseClassFields     = tpe.typeSymbol.caseFields
    val caseClassFieldNames = caseClassFields.map(_.name)
    val refined             = refinementForLabels(caseClassFieldNames)
    val keys                = elemsWithAnnotation[A, key]
    val x = MethodType(MethodTypeKind.Plain)(keys.map((name, _) => name))(
      x =>
        keys.map: (_, tpe) =>
          tpe,
      x => TypeRepr.of[Metadata[A]#TypedFragment],
    )

    val ref3 = Refinement(refined, Metadata.getByKey, x)
    val res = ref3.asType match
      case '[t] =>
        '{
          val x = ${ summonTable[A] }
          new Metadata[A](
            $name,
            $columns,
            $alias,
          )(using x).asInstanceOf[t]
        }
    res
  end metadataOfImpl

  private def elemHasAnnotation[T: Type, A <: StaticAnnotation: Type](elemName: String)(using
      Quotes
  ): Expr[Boolean] =
    import quotes.reflect.*
    val a = TypeRepr.of[A].typeSymbol
    Expr:
      TypeRepr
        .of[T]
        .typeSymbol
        .primaryConstructor
        .paramSymss
        .head
        .find(sym => sym.name == elemName && sym.hasAnnotation(a))
        .isDefined
  end elemHasAnnotation

  private def elemsWithAnnotation[T: Type, A <: StaticAnnotation: Type](using
      Quotes
  ): List[(String, x$1.reflect.TypeRepr)] =
    import quotes.reflect.*
    val a   = TypeRepr.of[A].typeSymbol
    val tpe = TypeRepr.of[T]
    val elems = TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .filter(sym => sym.hasAnnotation(a))
      .map(sym => (sym.name, tpe.memberType(sym)))
    elems
  end elemsWithAnnotation

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

  private def summonTable[T <: Product: Type](using Quotes): Expr[Table[T]] =
    import quotes.reflect.*
    Expr
      .summon[Table[T]]
      .getOrElse:
        report.errorAndAbort(s"Could not find a Table instance for ${Type.show[T]}")
  end summonTable

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
      val paramType = tpe.memberType(param)
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

  // This method is used to refine the Metadata type with the field names/labels
  // Metadata is a structural type
  private def refinementForLabels(fieldNames: Seq[String])(using Quotes) =
    import quotes.reflect.*
    fieldNames.foldLeft(TypeRepr.of[Metadata])((t, n) => Refinement(t, n, TypeRepr.of[Column[?]]))

end Macros
