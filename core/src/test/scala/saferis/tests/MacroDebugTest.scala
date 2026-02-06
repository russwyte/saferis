package saferis.tests

import saferis.*
import zio.json.*

// Standalone test to debug macro annotation handling

object MacroDebugTest:
  // Generic case class with @label
  @tableName("debug_generic")
  final case class DebugGeneric[E: JsonCodec](
      @generated @key id: Int,
      @label("instance_id") instanceId: String,
  )
  object DebugGeneric:
    given [E: JsonCodec]: Table[DebugGeneric[E]] = Table.derived

  // Non-generic case class with @label
  @tableName("debug_concrete")
  final case class DebugConcrete(
      @generated @key id: Int,
      @label("instance_id") instanceId: String,
  ) derives Table

  final case class Payload(x: Int) derives JsonCodec

  def main(args: Array[String]): Unit =
    val genericTable  = summon[Table[DebugGeneric[Payload]]]
    val concreteTable = summon[Table[DebugConcrete]]

    // Check class-level @tableName annotation
    val genericInstance  = genericTable.instance
    val concreteInstance = concreteTable.instance

    println("=== GENERIC ===")
    println(s"  tableName: '${genericInstance.tableName}'")
    genericTable.columns.foreach { c =>
      println(s"  ${c.name} -> label='${c.label}', key=${c.isKey}, generated=${c.isGenerated}")
    }

    println("=== CONCRETE ===")
    println(s"  tableName: '${concreteInstance.tableName}'")
    concreteTable.columns.foreach { c =>
      println(s"  ${c.name} -> label='${c.label}', key=${c.isKey}, generated=${c.isGenerated}")
    }
end MacroDebugTest
