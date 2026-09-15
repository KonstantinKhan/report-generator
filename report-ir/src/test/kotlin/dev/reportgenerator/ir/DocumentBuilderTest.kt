package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class Kind { ASSEMBLY, PART }
private data class Item(val name: String, val kind: Kind)

class DocumentBuilderTest {

    @Test
    fun `three items split into two groups produce three rows total`() {
        val items = listOf(
            Item("Корпус", Kind.ASSEMBLY),
            Item("Вал", Kind.PART),
            Item("Крышка", Kind.PART)
        )

        val doc = document {
            title("Спецификация")

            table {
                columns {
                    column("designation", width = 35.mm)
                    column("name", width = 80.mm)
                }

                group("Сборочные единицы") {
                    items.filter { it.kind == Kind.ASSEMBLY }.forEach { row(it.name) }
                }

                group("Детали") {
                    items.filter { it.kind == Kind.PART }.forEach { row(it.name) }
                }
            }
        }

        val table = doc.elements.filterIsInstance<IrTable>().single()
        val groups = table.content.filterIsInstance<IrGroup>()

        assertEquals(2, groups.size)
        assertEquals(3, groups.sumOf { it.rows.size })
        assertEquals(1, groups.first { it.title == "Сборочные единицы" }.rows.size)
        assertEquals(2, groups.first { it.title == "Детали" }.rows.size)
    }

    @Test
    fun `title produces leading IrText element`() {
        val doc = document {
            title("Спецификация")
            table { columns { } }
        }

        val first = doc.elements.first()
        assertTrue(first is IrText)
        assertEquals("Спецификация", first.text)
    }

    @Test
    fun `empty group produces no rows`() {
        val doc = document {
            table {
                columns { }
                group("Пусто") { }
            }
        }

        val table = doc.elements.filterIsInstance<IrTable>().single()
        val group = table.content.filterIsInstance<IrGroup>().single()
        assertEquals(0, group.rows.size)
    }
}
