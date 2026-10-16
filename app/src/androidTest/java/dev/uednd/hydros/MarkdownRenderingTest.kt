package dev.uednd.hydros

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.ui.components.Markdown
import dev.uednd.hydros.ui.legal.LegalDocuments
import dev.uednd.hydros.ui.theme.HydrosTheme
import dev.uednd.hydros.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownRenderingTest {
    @get:Rule val ui = createComposeRule()

    @Test fun legalListBaselinesAlignInLightTheme() = checkLegalList(ThemeMode.LIGHT, 1f)

    @Test fun legalListBaselinesAlignWithLargeTextInDarkTheme() = checkLegalList(ThemeMode.DARK, 1.6f)

    private fun checkLegalList(mode: ThemeMode, fontScale: Float) {
        // Use the exact section from the reported screenshot, narrow enough to wrap.
        val content = LegalDocuments.TERMS_OF_SERVICE.substringAfter("### 七、").let { "### 七、$it" }
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HydrosTheme(mode) {
                    androidx.compose.material3.Surface {
                        Column(Modifier.width(300.dp)) {
                            Markdown(content, lazy = true)
                        }
                    }
                }
            }
        }
        waitFor("生效要件", substring = true)
        val marker = ui.onNode(SemanticsMatcher("first list marker") {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.trim() == "1." } == true
        })
        val paragraph = ui.onNodeWithText("生效要件", substring = true)
        fun baseline(node: SemanticsNodeInteraction): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        assertEquals("List marker and first body line must share a baseline", baseline(marker), baseline(paragraph), 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The regression fixture must wrap", layouts.single().lineCount > 1)
        screenshot("markdown-${mode.name.lowercase()}-$fontScale")
    }

    @Test fun legalDocumentsReachTheirEndWithSelectionOnSmallDarkLargeTextScreen() {
        var document by androidx.compose.runtime.mutableStateOf(LegalDocuments.TERMS_OF_SERVICE)
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                HydrosTheme(ThemeMode.DARK) {
                    androidx.compose.material3.Surface(Modifier.width(320.dp)) {
                        dev.uednd.hydros.ui.screens.LegalDocumentScreen(document)
                    }
                }
            }
        }
        for ((content, last) in listOf(
            LegalDocuments.TERMS_OF_SERVICE to "协议终止",
            LegalDocuments.PRIVACY_POLICY to "您可以在系统设置中随时撤销"
        )) {
            ui.runOnIdle { document = content }
            waitFor(if (content == LegalDocuments.TERMS_OF_SERVICE) "Hydros 服务协议" else "Hydros 隐私政策")
            ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(last, substring = true))
            ui.onNodeWithText(last, substring = true).assertIsDisplayed()
            ui.onNodeWithText(last, substring = true).performTouchInput { longClick() }
            screenshot(if (content == LegalDocuments.TERMS_OF_SERVICE) "legal-terms-dark-large-end" else "legal-privacy-dark-large-end")
        }
    }

    @Test fun rendersGfmNestedListsAndOpensLinks() {
        var opened: String? = null
        ui.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                HydrosTheme {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        Markdown("""
                            # 更新说明

                            1. 父列表
                               - 子列表

                            | 功能 | 状态 |
                            | --- | --- |
                            | 表格渲染 | 完成 |

                            ~~旧说明~~

                            [打开项目](https://github.com/UE-DND/Hydros)
                        """.trimIndent())
                    }
                }
            }
        }
        waitFor("表格渲染", substring = true)
        ui.onNodeWithText("父列表").assertExists()
        ui.onNodeWithText("子列表").assertExists()
        ui.onNodeWithText("完成", substring = true).assertExists()
        ui.onNodeWithText("旧说明").assertExists()
        ui.onNodeWithText("打开项目").performScrollTo().performClick()
        ui.runOnIdle { assertEquals("https://github.com/UE-DND/Hydros", opened) }
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        ui.waitUntil(8_000) { ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
