package saferis

import java.sql.SQLException
import scala.annotation.StaticAnnotation
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
              val reader     = summonDecoder[a]
              val writer     = summonEncoder[a]
              val isNullable = isOptionType[a]
              val defaultVal = getDefaultValue[A, a](fieldName)

              '{
                val label       = ${ getLabel[A](fieldName) }
                val isKey       = ${ elemHasAnnotation[A, saferis.key](fieldName) }
                val isGenerated = ${ elemHasAnnotation[A, saferis.generated](fieldName) }

                Column[a](
                  ${ Expr(fieldName) },
                  label,
                  isKey,
                  isGenerated,
                  $isNullable,
                  $defaultVal,
                  None,
                )(using
                  $reader,
                  $writer,
                )
              }
      end match
    }
    Expr.ofSeq(columns)
  end columnsOfImpl

  private[saferis] transparent inline def instanceOf[A <: Product](alias: Option[String]) =
    ${ instanceImpl[A]('alias) }

  private def instanceImpl[A <: Product: Type](alias: Expr[Option[String]])(using Quotes) =
    import quotes.reflect.*
    val columns         = columnsOfImpl[A]
    val name            = nameOfImpl[A]
    val tpe             = TypeRepr.of[A]
    val caseClassFields = tpe.typeSymbol.caseFields
    // Collect both field names and their types for type-safe refinements
    val caseClassFieldNamesAndTypes = caseClassFields.map(f => (f.name, tpe.memberType(f)))
    val refined                     = refinementForLabels(caseClassFieldNamesAndTypes)
    val keys                        = elemsWithAnnotation[A, key]
    val x                           = MethodType(MethodTypeKind.Plain)(keys.map((name, _) => name))(
      _ =>
        keys.map: (_, tpe) =>
          tpe,
      _ => TypeRepr.of[Instance[A]#TypedFragment],
    )

    val ref3 = Refinement(refined, Instance.getByKey, x)
    val res  = ref3.asType match
      case '[t] =>
        '{
          val x = ${ summonTable[A] }
          new Instance[A](
            $name,
            $columns,
            $alias,
            Vector.empty,
            Vector.empty,
            Vector.empty,
          )(using x).asInstanceOf[t]
        }
    res
  end instanceImpl

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

  private def isOptionType[T: Type](using Quotes): Expr[Boolean] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    Expr(tpe.typeSymbol == TypeRepr.of[Option[?]].typeSymbol)

  private def elemsWithAnnotation[T: Type, A <: StaticAnnotation: Type](using
      Quotes
  ): List[(String, x$1.reflect.TypeRepr)] =
    import quotes.reflect.*
    val a     = TypeRepr.of[A].typeSymbol
    val tpe   = TypeRepr.of[T]
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

  private def getDefaultValue[T: Type, A: Type](elemName: String)(using
      Quotes
  ): Expr[Option[A]] =
    import quotes.reflect.*
    val tpe        = TypeRepr.of[T]
    val typeSymbol = tpe.typeSymbol
    val companion  = typeSymbol.companionModule
    val params     = typeSymbol.primaryConstructor.paramSymss.head
    val paramIndex = params.indexWhere(_.name == elemName)

    if paramIndex < 0 then Expr(None)
    else
      val param      = params(paramIndex)
      val hasDefault = param.flags.is(Flags.HasDefault)

      if hasDefault then
        // In Scala 3, the default method is named $lessinit$greater$default$N (1-indexed)
        // This corresponds to <init>$default$N but with angle brackets encoded
        val defaultMethodName = s"$$lessinit$$greater$$default$$${paramIndex + 1}"

        // Try to find the default method on the companion object using declarations
        if companion != Symbol.noSymbol then
          // First try methodMember
          val methodOpt = companion
            .methodMember(defaultMethodName)
            .headOption
            .orElse {
              // Fall back to searching declarations directly
              companion.declarations.find(_.name == defaultMethodName)
            }

          methodOpt match
            case Some(defaultMethod) =>
              try
                val defaultExpr = Select(Ref(companion), defaultMethod).asExprOf[A]
                '{ Some($defaultExpr) }
              catch
                case _: Exception =>
                  Expr(None)
            case None =>
              Expr(None)
          end match
        else Expr(None)
        end if
      else Expr(None)
      end if
    end if
  end getDefaultValue

  private def summonDecoder[T: Type](using Quotes): Expr[Decoder[T]] =
    import quotes.reflect.*
    Expr
      .summon[Decoder[T]]
      .orElse(
        TypeRepr.of[T].widen.asType match
          case '[tpe] =>
            Expr
              .summon[Decoder[tpe]]
              .map(d => '{ $d.asInstanceOf[Decoder[T]] })
      )
      .orElse(
        TypeRepr.of[T].widen.asType match
          case '[tpe] =>
            Expr
              .summon[Codec[tpe]]
              .map(codec => '{ $codec.asInstanceOf[Decoder[T]] })
      )
      .orElse(
        // Fallback: if JsonCodec[T] exists, derive a Decoder from it (JSONB support)
        Expr.summon[zio.json.JsonCodec[T]].map { codec =>
          '{ Decoder.fromJsonCodec[T](using $codec) }
        }
      )
      .getOrElse:
        report.errorAndAbort(s"Could not find a Decoder for ${Type.show[T]}")
  end summonDecoder

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
                      s"Error constructing instance of ${${ Expr(typeName) }}. Could not find value for parameter ${${
                          Expr(paramName)
                        }}"
                    )
              }
      end match
    }

    Apply(Select(Ref(companion), applyMethod), argsExprs.map(_.asTerm).toList).asExprOf[A]
  end makeImpl

  // This method is used to refine the Instance type with the field names/labels
  // Instance is a structural type - we preserve the actual field types for type-safe column access
  private def refinementForLabels(using q: Quotes)(fieldNamesAndTypes: Seq[(String, q.reflect.TypeRepr)]) =
    import q.reflect.*
    fieldNamesAndTypes.foldLeft(TypeRepr.of[Instance]): (t, nt) =>
      val (name, fieldType) = nt
      // Create Column[FieldType] refinement to preserve type information
      val columnType = TypeRepr.of[Column].appliedTo(fieldType)
      Refinement(t, name, columnType)

  private[saferis] def summonEncoder[T: Type](using Quotes): Expr[Encoder[T]] =
    import quotes.reflect.*
    Expr
      .summon[Encoder[T]]
      .orElse(
        TypeRepr.of[T].widen.asType match
          case '[tpe] =>
            Expr
              .summon[Encoder[tpe]]
              .map(e => '{ $e.asInstanceOf[Encoder[T]] })
      )
      .orElse(
        TypeRepr.of[T].widen.asType match
          case '[tpe] =>
            Expr
              .summon[Codec[tpe]]
              .map(codec => '{ $codec.asInstanceOf[Encoder[T]] })
      )
      .orElse(
        // Fallback: if JsonCodec[T] exists, derive an Encoder from it (JSONB support)
        Expr.summon[zio.json.JsonCodec[T]].map { codec =>
          '{ Encoder.fromJsonCodec[T](using $codec) }
        }
      )
      .getOrElse:
        report.errorAndAbort(s"Could not find Encoder for ${Type.show[T]}")
  end summonEncoder

  private[saferis] inline def columnPlaceholders[A <: Product](instance: A): List[(String, Placeholder)] = ${
    columnPlaceholdersImpl('instance)
  }

  private def columnPlaceholdersImpl[A <: Product: Type](instance: Expr[A])(using
      Quotes
  ): Expr[List[(String, Placeholder)]] =
    import quotes.reflect.*
    val tpe         = TypeRepr.of[A]
    val fields      = tpe.typeSymbol.caseFields
    val fieldValues = fields.map { field =>
      val fieldName  = field.name
      val fieldType  = tpe.memberType(field)
      val fieldValue =
        fieldType.asType match
          case '[ft] =>
            val f = Select(instance.asTerm, field).asExprOf[ft]
            val e = summonEncoder[ft]
            '{ Placeholder($f)(using $e) }

      '{ (${ Expr(fieldName) }, $fieldValue) }
    }

    Expr.ofList(fieldValues)
  end columnPlaceholdersImpl

  // === Field extraction macros for type-safe selectors ===

  /** Extract field name from a selector function like `_.fieldName`.
    *
    * This is used by Query for type-safe ON and WHERE conditions, and by ForeignKey for type-safe references.
    *
    * @param selector
    *   A lambda like `(a: A) => a.fieldName` or `_.fieldName`
    * @return
    *   The field name as a String
    */
  private[saferis] inline def extractFieldName[A, T](inline selector: A => T): String =
    ${ extractFieldNameImpl[A, T]('selector) }

  private[saferis] def extractFieldNameImpl[A: Type, T: Type](selector: Expr[A => T])(using Quotes): Expr[String] =
    val fieldName = extractFieldNameFromSelector(selector)
    Expr(fieldName)

  /** Internal helper to extract field name from selector - returns String directly for use in other macros */
  private[saferis] def extractFieldNameFromSelector[A: Type, T: Type](selector: Expr[A => T])(using Quotes): String =
    import quotes.reflect.*
    findFieldName(selector.asTerm)

  /** Recursively find the field name from a selector expression */
  private def findFieldName(using Quotes)(term: quotes.reflect.Term): String =
    import quotes.reflect.*
    term match
      // Handle: Block(List(DefDef(..., Some(Select(_, name)))), _)
      case Block(List(DefDef(_, _, _, Some(body))), _) =>
        extractFromBody(body)
      // Handle: Inlined(_, _, innerTerm)
      case Inlined(_, _, inner) =>
        findFieldName(inner)
      // Handle: Lambda(_, Select(_, name))
      case Lambda(_, body) =>
        extractFromBody(body)
      // Handle: Typed(inner, _) - wraps typed expressions
      case Typed(inner, _) =>
        findFieldName(inner)
      // Handle: Block containing just the lambda
      case Block(stats, expr) =>
        findFieldName(expr)
      case other =>
        report.errorAndAbort(
          s"Expected a simple field selector like _.fieldName, got: ${other.show} (class: ${other.getClass.getName})"
        )
    end match
  end findFieldName

  private def extractFromBody(using Quotes)(body: quotes.reflect.Term): String =
    import quotes.reflect.*
    body match
      case Select(_, fieldName) => fieldName
      case Inlined(_, _, inner) => extractFromBody(inner)
      case Typed(inner, _)      => extractFromBody(inner)
      case other                =>
        report.errorAndAbort(
          s"Expected a field access like _.fieldName, got: ${other.show} (class: ${other.getClass.getName})"
        )
  end extractFromBody

end Macros
