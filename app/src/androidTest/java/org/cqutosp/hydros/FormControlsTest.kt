package org.cqutosp.hydros

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.cqutosp.hydros.ui.components.FormField
import org.cqutosp.hydros.ui.components.HydrosSnackbarProvider
import org.cqutosp.hydros.ui.screens.AccountForm
import org.cqutosp.hydros.ui.screens.AccountFormScreen
import org.cqutosp.hydros.ui.screens.LoginScreen
import org.cqutosp.hydros.ui.screens.MineScreen
import org.cqutosp.hydros.ui.state.AccountUi
import org.cqutosp.hydros.ui.state.HydrosUiState
import org.cqutosp.hydros.ui.theme.HydrosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FormControlsTest {
    @get:Rule val ui = createComposeRule()

    @Test fun loginDoesNotLimitInputOrShowCounters() {
        ui.setContent { HydrosTheme { Surface { LoginScreen() } } }
        val phone = ui.onNodeWithContentDescription("手机号")
        phone.performTextInput("138001380001")
        phone.assertTextEquals("138001380001")
        ui.onNodeWithText("12/11").assertDoesNotExist()

        val password = ui.onNodeWithContentDescription("密码")
        val longPassword = "a".repeat(33)
        password.performTextInput(longPassword)
        ui.onNodeWithContentDescription("显示密码").performClick()
        password.assertTextEquals(longPassword)
        ui.onNodeWithText("33/32").assertDoesNotExist()

        ui.onNodeWithText("验证码登录").performClick()
        val code = ui.onNodeWithContentDescription("验证码")
        code.performTextInput("1234567")
        code.assertTextEquals("1234567")
        ui.onNodeWithText("7/6").assertDoesNotExist()
    }

    @Test fun limitedFieldRejectsOverflowAndAllowsReplacementAndDeletion() {
        ui.setContent {
            HydrosTheme {
                var value by remember { mutableStateOf("") }
                Surface { FormField(value, { value = it }, "验证码", maxLength = 6, error = "校验提示") }
            }
        }
        val code = ui.onNodeWithContentDescription("验证码")
        code.performTextInput("123456")
        code.performTextInput("7")
        code.assertTextEquals("123456")
        ui.onNodeWithText("6/6").assertExists()
        ui.onNodeWithText("校验提示").assertDoesNotExist()
        ui.onNodeWithContentDescription("验证码").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "校验提示"))
        code.performTextReplacement("654321")
        code.assertTextEquals("654321")
        code.performTextClearance()
        ui.onNodeWithText("0/6").assertExists()
        code.performTextInput("1234567")
        code.assertTextEquals("")
    }

    @Test fun fieldWithoutLimitKeepsLongInputAndValidationMessage() {
        ui.setContent {
            HydrosTheme {
                var value by remember { mutableStateOf("") }
                Surface { FormField(value, { value = it }, "备注", error = "校验提示") }
            }
        }
        val text = "测试".repeat(100)
        ui.onNodeWithContentDescription("备注").performTextInput(text)
        ui.onNodeWithContentDescription("备注").assertTextEquals(text)
        ui.onNodeWithText("校验提示").assertDoesNotExist()
        ui.onNodeWithContentDescription("备注").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "校验提示"))
    }

    @Test fun changePhoneFormDoesNotLimitInputOrShowCounters() {
        val testAccount = AccountUi(phone = "13800138000")
        ui.setContent {
            HydrosTheme {
                Surface {
                    AccountFormScreen(
                        form = AccountForm.PHONE,
                        account = testAccount,
                        onSend = { _, _ -> true },
                        onSave = { _, _, _, _ -> true }
                    )
                }
            }
        }
        // 验证页面内不包含重复标题与下方文案
        ui.onNodeWithText("更换手机号").assertDoesNotExist()
        ui.onNodeWithText("验证码发送至准备绑定的新号码，修改后需要重新登录。").assertDoesNotExist()

        val newPhone = ui.onNodeWithContentDescription("新手机号")
        newPhone.performTextInput("138001380001")
        newPhone.assertTextEquals("138001380001")
        ui.onNodeWithText("12/11").assertDoesNotExist()

        val phoneCode = ui.onNodeWithContentDescription("验证码")
        phoneCode.performTextInput("1234567")
        phoneCode.assertTextEquals("1234567")
        ui.onNodeWithText("7/6").assertDoesNotExist()
    }

    @Test fun changePhoneFormSubmitsEnteredSmsCode() {
        val testAccount = AccountUi(phone = "13800138000")
        var submittedPhone = ""
        var submittedCode = ""
        ui.setContent {
            HydrosTheme {
                Surface {
                    AccountFormScreen(
                        form = AccountForm.PHONE,
                        account = testAccount,
                        onSend = { _, _ -> true },
                        onSave = { phone, _, _, code ->
                            submittedPhone = phone
                            submittedCode = code
                            true
                        }
                    )
                }
            }
        }
        ui.onNodeWithContentDescription("新手机号").performTextInput("13900139000")
        ui.onNodeWithContentDescription("验证码").performTextInput("654321")
        ui.onNodeWithText("保存修改").performClick()
        ui.runOnIdle {
            assertEquals("13900139000", submittedPhone)
            assertEquals("654321", submittedCode)
        }
    }

    @Test fun changePasswordFormSwitchesTabsAndDoesNotLimitInput() {
        val testAccount = AccountUi(phone = "13800138000")
        val longPassword = "a".repeat(33)
        ui.setContent {
            HydrosTheme {
                Surface {
                    AccountFormScreen(
                        form = AccountForm.PASSWORD,
                        account = testAccount,
                        onSend = { _, _ -> true },
                        onSave = { _, _, _, _ -> true }
                    )
                }
            }
        }
        // 验证页面内不包含重复标题与下方文案
        ui.onNodeWithText("修改密码").assertDoesNotExist()
        ui.onNodeWithText("输入当前密码与新密码。").assertDoesNotExist()
        ui.onNodeWithText("通过当前绑定手机号设置新密码。").assertDoesNotExist()

        // 1. 默认：通过当前密码修改
        ui.onNodeWithText("通过当前密码修改").assertExists()
        ui.onNodeWithText("通过短信验证码修改").assertExists()
        val oldPassword = ui.onNodeWithContentDescription("当前密码")
        oldPassword.performTextInput(longPassword)
        val newPassword = ui.onNodeWithContentDescription("新密码")
        newPassword.performTextInput(longPassword)
        val repeatedPassword = ui.onNodeWithContentDescription("确认新密码")
        repeatedPassword.performTextInput(longPassword)

        while (ui.onAllNodesWithContentDescription("显示密码").fetchSemanticsNodes().isNotEmpty()) {
            ui.onAllNodesWithContentDescription("显示密码")[0].performClick()
        }
        oldPassword.assertTextEquals(longPassword)
        newPassword.assertTextEquals(longPassword)
        repeatedPassword.assertTextEquals(longPassword)
        ui.onNodeWithText("33/32").assertDoesNotExist()

        // 2. 切换到：通过短信验证码修改
        ui.onNodeWithText("通过短信验证码修改").performClick()
        ui.onNodeWithContentDescription("当前密码").assertDoesNotExist()
        ui.onNodeWithText("当前手机号：13800138000").assertExists()

        val resetCode = ui.onNodeWithContentDescription("验证码")
        resetCode.performTextInput("1234567")
        resetCode.assertTextEquals("1234567")
        ui.onNodeWithText("7/6").assertDoesNotExist()

        val resetNewPassword = ui.onNodeWithContentDescription("新密码")
        resetNewPassword.performTextReplacement(longPassword)
        val resetRepeatedPassword = ui.onNodeWithContentDescription("确认新密码")
        resetRepeatedPassword.performTextReplacement(longPassword)

        while (ui.onAllNodesWithContentDescription("显示密码").fetchSemanticsNodes().isNotEmpty()) {
            ui.onAllNodesWithContentDescription("显示密码")[0].performClick()
        }
        resetNewPassword.assertTextEquals(longPassword)
        resetRepeatedPassword.assertTextEquals(longPassword)
        ui.onNodeWithText("33/32").assertDoesNotExist()

        // 3. 切回：通过当前密码修改
        ui.onNodeWithText("通过当前密码修改").performClick()
        ui.onNodeWithContentDescription("当前密码").assertExists()
        ui.onNodeWithContentDescription("验证码").assertDoesNotExist()
    }

    @Test fun mineScreenOnlyShowsManageAccountEntry() {
        ui.setContent {
            HydrosTheme {
                HydrosSnackbarProvider {
                    Surface {
                        MineScreen(
                            state = HydrosUiState(),
                            onManageAccount = {},
                            onAppearance = {},
                            onAbout = {}
                        )
                    }
                }
            }
        }
        ui.onNodeWithText("管理账户").assertExists()
        ui.onNodeWithText("退出账户").assertDoesNotExist()
        ui.onNodeWithText("更换手机号").assertDoesNotExist()
        ui.onNodeWithText("修改密码").assertDoesNotExist()
        ui.onNodeWithText("设置或重置密码").assertDoesNotExist()
    }
}
