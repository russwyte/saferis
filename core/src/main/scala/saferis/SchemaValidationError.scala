package saferis

/** Error thrown when schema validation fails.
  *
  * @param issues
  *   The list of issues found during validation
  */
final case class SchemaValidationError(issues: List[SchemaIssue]) extends Exception:
  override def getMessage: String =
    val count   = issues.length
    val summary = if count == 1 then "1 issue" else s"$count issues"
    s"Schema validation failed with $summary:\n${issues.map(i => s"  - ${i.description}").mkString("\n")}"
end SchemaValidationError
