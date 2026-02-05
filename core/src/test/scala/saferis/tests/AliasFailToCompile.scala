package saferis.tests

import saferis.*

// This file demonstrates that non-literal strings fail to compile.
// UNCOMMENT ANY BLOCK BELOW TO SEE THE COMPILE ERROR:
//
//   "Alias requires a string literal. For runtime identifiers,
//    use Placeholder.identifier() or dialect.escapeIdentifier() instead."

object AliasFailToCompile:

  @tableName("test_users")
  final case class TestUser(@key id: Int, name: String) derives Table

  // This compiles - string literal
  val goodAlias = Alias("users")

  // === FAILURE CASE 1: Alias(variable) ===
  // val runtimeAlias = "users"
  // val badAlias = Alias(runtimeAlias)

  // === FAILURE CASE 2: Table[A](variable) ===
  // val variable = "u"
  // val badInstance = Table[TestUser](variable)

  // === FAILURE CASE 3: instance as variable ===
  // val instance = Table[TestUser]
  // val alias = "u"
  // val badAliased = instance as alias

  // === FAILURE CASE 4: aliased(instance, variable) ===
  // val inst = Table[TestUser]
  // val a = "u"
  // val badAliasedFunc = aliased(inst, a)

  // === FAILURE CASE 5: Query.from(subquery, variable) ===
  // val subquery = Query[TestUser].where(_.id).gt(0).selectAll[TestUser]
  // val derivedAlias = "derived"
  // val badQuery = Query.from(subquery, derivedAlias)

end AliasFailToCompile
