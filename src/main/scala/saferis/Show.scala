package saferis

import scala.quoted.*

inline def show[T](inline x: T) = ${ showImpl('x) }

private def showImpl[T: Type](x: Expr[T])(using Quotes): Expr[T] =
  import quotes.reflect.*
  val term = x.asTerm
  val tp   = Type.show
  val code = term.show(using Printer.TreeAnsiCode)
  val tree = term.show(using Printer.TreeStructure)
  println(s"Type: $tp\n\nRepr: $code\n\nTree: $tree")
  x
end showImpl
