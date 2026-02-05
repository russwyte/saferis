package saferis

import scala.quoted.*

/** Type-safe representation of SQL table aliases.
  *
  * Aliases are used to qualify column references in SQL queries. The `Alias.apply` constructor is a macro that only
  * accepts string literals - this guarantees at compile time that aliases cannot contain SQL injection vectors.
  *
  * ==Compile-time Safety==
  * {{{
  *   Alias("users")           // OK - string literal
  *   Alias("u")               // OK - string literal
  *   val s = "users"
  *   Alias(s)                 // COMPILE ERROR - not a literal
  *   Alias(getUserInput())    // COMPILE ERROR - not a literal
  * }}}
  *
  * ==For Runtime Identifiers==
  * If you need to use a runtime-determined identifier (e.g., from user input or configuration), use the dialect's
  * `escapeIdentifier` method or `Placeholder.identifier`:
  * {{{
  *   import saferis.Placeholder.identifier
  *   val userInput = request.getParameter("column")
  *   sql"SELECT * FROM users ORDER BY \${identifier(userInput)}"  // Safely escaped
  * }}}
  */
final case class Alias private[saferis] (value: String):
  /** Convert the alias to its SQL representation */
  def toSql: String = value

object Alias:
  /** Create an alias from a string literal.
    *
    * This is a macro that enforces the argument is a compile-time string literal. This prevents SQL injection by
    * ensuring aliases can only come from source code, never from runtime input.
    */
  inline def apply(inline value: String): Alias =
    ${ applyImpl('value) }

  private def applyImpl(value: Expr[String])(using Quotes): Expr[Alias] =
    import quotes.reflect.*

    value.asTerm match
      case Inlined(_, _, Literal(StringConstant(s))) =>
        '{ Alias.fromLiteral(${ Expr(s) }) }
      case Literal(StringConstant(s)) =>
        '{ Alias.fromLiteral(${ Expr(s) }) }
      case _ =>
        report.errorAndAbort(
          "Alias requires a string literal. " +
            "For runtime identifiers, use Placeholder.identifier() or dialect.escapeIdentifier() instead."
        )
    end match
  end applyImpl

  /** Internal factory for macro use - DO NOT CALL DIRECTLY.
    *
    * This method exists because inline macros cannot access private constructors. Use `Alias("literal")` instead.
    */
  def fromLiteral(value: String): Alias = new Alias(value)

  /** Internal constructor for library use - bypasses the literal check.
    *
    * This is used internally when we know the value is safe (e.g., generated aliases like "users_ref_1").
    */
  private[saferis] def unsafe(value: String): Alias = new Alias(value)
end Alias
