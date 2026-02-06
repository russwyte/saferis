package saferis

/** Options for schema verification.
  *
  * @param checkExtraColumns
  *   Report columns in DB not defined in schema (drift detection)
  * @param checkIndexes
  *   Verify indexes exist
  * @param checkUniqueConstraints
  *   Verify unique constraints exist
  * @param checkForeignKeys
  *   Verify foreign keys exist
  * @param checkNullability
  *   Verify column nullable/NOT NULL matches
  * @param checkTypes
  *   Verify column types match (with type family compatibility)
  * @param strictTypeMatching
  *   Require exact type match instead of compatible types
  * @param strictNameMatching
  *   Fail if index/constraint exists but with different name than expected
  */
final case class VerifyOptions(
    checkExtraColumns: Boolean = true,
    checkIndexes: Boolean = true,
    checkUniqueConstraints: Boolean = true,
    checkForeignKeys: Boolean = true,
    checkNullability: Boolean = true,
    checkTypes: Boolean = true,
    strictTypeMatching: Boolean = false,
    strictNameMatching: Boolean = false,
)

object VerifyOptions:
  /** Default options - check everything except strict name matching */
  val default: VerifyOptions = VerifyOptions()

  /** Minimal options - only check table and columns exist */
  val minimal: VerifyOptions = VerifyOptions(
    checkExtraColumns = false,
    checkIndexes = false,
    checkUniqueConstraints = false,
    checkForeignKeys = false,
  )

  /** Strict options - check everything including names */
  val strict: VerifyOptions = VerifyOptions(
    strictTypeMatching = true,
    strictNameMatching = true,
  )
end VerifyOptions
