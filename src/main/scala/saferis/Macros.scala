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

    // First pass: collect all labels and types for validation
    val labelAnnotation                                       = TypeRepr.of[label].typeSymbol
    val fieldLabelsAndTypes: List[(String, String, TypeRepr)] = fields.map { field =>
      val fieldName = field.name
      val fieldType = tpe.memberType(field)
      // Get the label (either from @label annotation or the field name)
      val columnLabel = tpe.typeSymbol.primaryConstructor.paramSymss.head
        .find(sym => sym.name == fieldName && sym.hasAnnotation(labelAnnotation))
        .flatMap(sym => sym.getAnnotation(labelAnnotation))
        .collect { case Apply(_, List(Literal(StringConstant(name)))) => name }
        .getOrElse(fieldName)
      (fieldName, columnLabel, fieldType)
    }

    val allLabels: Set[String]             = fieldLabelsAndTypes.map(_._2).toSet
    val columnTypes: Map[String, TypeRepr] = fieldLabelsAndTypes.map { case (_, label, tpe) =>
      label.toLowerCase -> tpe
    }.toMap

    // Second pass: validate conditions and build columns
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

              // Extract conditions from annotations (runtime expressions)
              val indexConditionExpr       = getIndexCondition[A](fieldName)
              val uniqueIndexConditionExpr = getUniqueIndexCondition[A](fieldName)

              // Validate index condition at compile time using literal extraction
              extractIndexConditionLiteral[A](fieldName).foreach { cond =>
                validateConditionColumns(cond, allLabels, "indexed", fieldName)
                validateConditionTypes(cond, columnTypes, "indexed", fieldName)
              }

              // Validate unique index condition at compile time using literal extraction
              extractUniqueIndexConditionLiteral[A](fieldName).foreach { cond =>
                validateConditionColumns(cond, allLabels, "uniqueIndex", fieldName)
                validateConditionTypes(cond, columnTypes, "uniqueIndex", fieldName)
              }

              '{
                val label                = ${ getLabel[A](fieldName) }
                val isKey                = ${ elemHasAnnotation[A, saferis.key](fieldName) }
                val isGenerated          = ${ elemHasAnnotation[A, saferis.generated](fieldName) }
                val isIndexed            = ${ elemHasAnnotation[A, saferis.indexed](fieldName) }
                val isUniqueIndex        = ${ elemHasAnnotation[A, saferis.uniqueIndex](fieldName) }
                val isUnique             = ${ elemHasAnnotation[A, saferis.unique](fieldName) }
                val uniqueGroup          = ${ getUniqueGroup[A](fieldName) }
                val indexGroup           = ${ getIndexGroup[A](fieldName) }
                val uniqueIndexGroup     = ${ getUniqueIndexGroup[A](fieldName) }
                val indexCondition       = $indexConditionExpr
                val uniqueIndexCondition = $uniqueIndexConditionExpr

                Column[a](
                  ${ Expr(fieldName) },
                  label,
                  isKey,
                  isGenerated,
                  isIndexed,
                  isUniqueIndex,
                  isUnique,
                  $isNullable,
                  uniqueGroup,
                  indexGroup,
                  uniqueIndexGroup,
                  indexCondition,
                  uniqueIndexCondition,
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
    val columns             = columnsOfImpl[A]
    val name                = nameOfImpl[A]
    val tpe                 = TypeRepr.of[A]
    val caseClassFields     = tpe.typeSymbol.caseFields
    val caseClassFieldNames = caseClassFields.map(_.name)
    val refined             = refinementForLabels(caseClassFieldNames)
    val keys                = elemsWithAnnotation[A, key]
    val x                   = MethodType(MethodTypeKind.Plain)(keys.map((name, _) => name))(
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

  private def getUniqueGroup[T: Type](elemName: String)(using
      Quotes
  ): Expr[Option[String]] =
    import quotes.reflect.*
    val a = TypeRepr.of[unique].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .flatMap { term =>
        // Extract the name parameter from @unique("name") or @unique(name = "name")
        term match
          case Apply(_, List(Literal(StringConstant(name)))) if name.nonEmpty              => Some(Expr(Some(name)))
          case Apply(_, List(NamedArg(_, Literal(StringConstant(name))))) if name.nonEmpty => Some(Expr(Some(name)))
          case _                                                                           => None
      }
      .getOrElse(Expr(None))
  end getUniqueGroup

  private def getIndexGroup[T: Type](elemName: String)(using
      Quotes
  ): Expr[Option[String]] =
    import quotes.reflect.*
    val a = TypeRepr.of[indexed].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .map { term =>
        // Cast to indexed type and access .name field directly
        val ann = term.asExprOf[indexed]
        '{ if $ann.name.nonEmpty then Some($ann.name) else None }
      }
      .getOrElse(Expr(None))
  end getIndexGroup

  private def getUniqueIndexGroup[T: Type](elemName: String)(using
      Quotes
  ): Expr[Option[String]] =
    import quotes.reflect.*
    val a = TypeRepr.of[uniqueIndex].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .map { term =>
        // Cast to uniqueIndex type and access .name field directly
        val ann = term.asExprOf[uniqueIndex]
        '{ if $ann.name.nonEmpty then Some($ann.name) else None }
      }
      .getOrElse(Expr(None))
  end getUniqueIndexGroup

  private def getIndexCondition[T: Type](elemName: String)(using
      Quotes
  ): Expr[Option[String]] =
    import quotes.reflect.*
    val a = TypeRepr.of[indexed].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .map { term =>
        // Cast to indexed type and access .condition field directly
        val ann = term.asExprOf[indexed]
        '{ if $ann.condition.nonEmpty then Some($ann.condition) else None }
      }
      .getOrElse(Expr(None))
  end getIndexCondition

  private def getUniqueIndexCondition[T: Type](elemName: String)(using
      Quotes
  ): Expr[Option[String]] =
    import quotes.reflect.*
    val a = TypeRepr.of[uniqueIndex].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .map { term =>
        // Cast to uniqueIndex type and access .condition field directly
        val ann = term.asExprOf[uniqueIndex]
        '{ if $ann.condition.nonEmpty then Some($ann.condition) else None }
      }
      .getOrElse(Expr(None))
  end getUniqueIndexCondition

  // Helper to extract condition literal at compile-time for validation
  private def extractIndexConditionLiteral[T: Type](elemName: String)(using
      Quotes
  ): Option[String] =
    import quotes.reflect.*
    val a = TypeRepr.of[indexed].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .flatMap { term =>
        // Try to extract the condition string from the AST
        term match
          case Apply(_, args) if args.length >= 2 =>
            args(1) match
              case Literal(StringConstant(cond)) if cond.nonEmpty => Some(cond)
              case _                                              => None
          case _ => None
      }
  end extractIndexConditionLiteral

  // Helper to extract unique index condition literal at compile-time for validation
  private def extractUniqueIndexConditionLiteral[T: Type](elemName: String)(using
      Quotes
  ): Option[String] =
    import quotes.reflect.*
    val a = TypeRepr.of[uniqueIndex].typeSymbol
    TypeRepr
      .of[T]
      .typeSymbol
      .primaryConstructor
      .paramSymss
      .head
      .find(sym => sym.name == elemName && sym.hasAnnotation(a))
      .flatMap(sym => sym.getAnnotation(a))
      .flatMap { term =>
        // Try to extract the condition string from the AST
        term match
          case Apply(_, args) if args.length >= 2 =>
            args(1) match
              case Literal(StringConstant(cond)) if cond.nonEmpty => Some(cond)
              case _                                              => None
          case _ => None
      }
  end extractUniqueIndexConditionLiteral

  /** Validates that all column references in a WHERE condition exist in the table. Uses case-insensitive matching for
    * SQL keywords.
    */
  private def validateConditionColumns(
      condition: String,
      allLabels: Set[String],
      annotationName: String,
      fieldName: String,
  )(using Quotes): Unit =
    import quotes.reflect.*
    // Case-insensitive pattern for SQL operators - extracts column name before operators
    val columnPattern     = """(?i)(\w+)\s*(?:=|<>|!=|<=|>=|<|>|IS\s+(?:NOT\s+)?NULL|IN\s*\(|LIKE|BETWEEN)""".r
    val referencedColumns = columnPattern.findAllMatchIn(condition).map(_.group(1).toLowerCase).toSet
    // Filter out SQL keywords that might be matched
    val sqlKeywords    = Set("and", "or", "not", "is", "in", "like", "between", "null", "true", "false")
    val actualColumns  = referencedColumns -- sqlKeywords
    val invalidColumns = actualColumns -- allLabels.map(_.toLowerCase)
    if invalidColumns.nonEmpty then
      report.errorAndAbort(
        s"@$annotationName condition on field '$fieldName' references unknown column(s): ${invalidColumns.mkString(", ")}. " +
          s"Available columns: ${allLabels.mkString(", ")}"
      )
  end validateConditionColumns

  /** Validates that literal values in a WHERE condition are type-compatible with the referenced columns. Uses
    * case-insensitive matching for SQL keywords.
    */
  private def validateConditionTypes(using
      q: Quotes
  )(
      condition: String,
      columnTypes: Map[String, q.reflect.TypeRepr],
      annotationName: String,
      fieldName: String,
  ): Unit =
    import q.reflect.*

    def isStringType(tpe: TypeRepr): Boolean =
      tpe <:< TypeRepr.of[String] ||
        tpe <:< TypeRepr.of[Text] ||
        (tpe <:< TypeRepr.of[Option[?]] && tpe.typeArgs.headOption.exists(isStringType))

    def isNumericType(tpe: TypeRepr): Boolean =
      tpe <:< TypeRepr.of[Int] ||
        tpe <:< TypeRepr.of[Long] ||
        tpe <:< TypeRepr.of[Double] ||
        tpe <:< TypeRepr.of[Float] ||
        tpe <:< TypeRepr.of[Short] ||
        tpe <:< TypeRepr.of[Byte] ||
        tpe <:< TypeRepr.of[BigDecimal] ||
        tpe <:< TypeRepr.of[BigInt] ||
        (tpe <:< TypeRepr.of[Option[?]] && tpe.typeArgs.headOption.exists(isNumericType))

    def isBooleanType(tpe: TypeRepr): Boolean =
      tpe <:< TypeRepr.of[Boolean] ||
        (tpe <:< TypeRepr.of[Option[?]] && tpe.typeArgs.headOption.exists(isBooleanType))

    def isOptionType(tpe: TypeRepr): Boolean =
      tpe <:< TypeRepr.of[Option[?]]

    // Check string literals (e.g., status = 'pending')
    val stringLiteralPattern = """(?i)(\w+)\s*(?:=|!=|<>|LIKE)\s*'[^']*'""".r
    stringLiteralPattern.findAllMatchIn(condition).foreach { m =>
      val colName = m.group(1).toLowerCase
      columnTypes.get(colName).foreach { tpe =>
        if !isStringType(tpe) then
          report.errorAndAbort(
            s"@$annotationName condition on field '$fieldName': column '$colName' is not a String type, " +
              s"but condition uses string literal"
          )
      }
    }

    // Check numeric literals (e.g., count > 5)
    val numericLiteralPattern = """(?i)(\w+)\s*(?:=|!=|<>|<|>|<=|>=)\s*(\d+(?:\.\d+)?)(?!\s*')""".r
    numericLiteralPattern.findAllMatchIn(condition).foreach { m =>
      val colName = m.group(1).toLowerCase
      columnTypes.get(colName).foreach { tpe =>
        if !isNumericType(tpe) then
          report.errorAndAbort(
            s"@$annotationName condition on field '$fieldName': column '$colName' is not a numeric type, " +
              s"but condition uses numeric literal"
          )
      }
    }

    // Check boolean literals (e.g., active = true)
    val booleanPattern = """(?i)(\w+)\s*(?:=|!=)\s*(true|false)(?!\s*')""".r
    booleanPattern.findAllMatchIn(condition).foreach { m =>
      val colName = m.group(1).toLowerCase
      columnTypes.get(colName).foreach { tpe =>
        if !isBooleanType(tpe) then
          report.errorAndAbort(
            s"@$annotationName condition on field '$fieldName': column '$colName' is not a Boolean type, " +
              s"but condition uses boolean literal"
          )
      }
    }

    // Check IS NULL usage (should be Option[_] type)
    val nullPattern = """(?i)(\w+)\s+IS\s+(?:NOT\s+)?NULL""".r
    nullPattern.findAllMatchIn(condition).foreach { m =>
      val colName = m.group(1).toLowerCase
      columnTypes.get(colName).foreach { tpe =>
        if !isOptionType(tpe) then
          report.errorAndAbort(
            s"@$annotationName condition on field '$fieldName': column '$colName' is not nullable (Option type), " +
              s"but condition uses IS NULL"
          )
      }
    }
  end validateConditionTypes

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
  // Instance is a structural type
  private def refinementForLabels(fieldNames: Seq[String])(using Quotes) =
    import quotes.reflect.*
    fieldNames.foldLeft(TypeRepr.of[Instance])((t, n) => Refinement(t, n, TypeRepr.of[Column[?]]))

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

end Macros
