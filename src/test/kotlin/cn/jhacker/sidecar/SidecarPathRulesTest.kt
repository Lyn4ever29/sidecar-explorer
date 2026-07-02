package cn.jhacker.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals

class SidecarPathRulesTest {
    @Test
    fun `normalize path trims whitespace and trailing slash`() {
        assertEquals("/tmp/demo", SidecarPathRules.normalizePath(" /tmp/demo/ "))
    }

    @Test
    fun `directories sort before files and names sort ascending`() {
        val names = listOf(
            TestFile("zeta.txt", false),
            TestFile("src", true),
            TestFile("README.md", false),
            TestFile("app", true)
        )

        val sorted = names.sortedWith { left, right ->
            SidecarPathRules.compareNames(left.name, left.directory, right.name, right.directory)
        }

        assertEquals(listOf("app", "src", "README.md", "zeta.txt"), sorted.map { it.name })
    }

    private data class TestFile(val name: String, val directory: Boolean)
}
