package dev.uednd.hydros.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.state.*

enum class AccountForm(val title: String) { PHONE("更换手机号"), PASSWORD("修改密码") }

@Composable
fun ManageAccountScreen(
    state: HydrosUiState,
    onPhone: () -> Unit,
    onPassword: () -> Unit,
    onLogout: () -> Unit,
    onSession: () -> Unit,
) {
    val snackbar = LocalHydrosSnackbar.current
    var logoutSheet by remember { mutableStateOf(false) }
    PageColumn {
        Section("账户安全") {
            MineRow("更换手机号", icon = R.drawable.ic_phone, tone = IconTone.PRIMARY, onClick = onPhone)
            SubtleDivider()
            MineRow("修改密码", icon = R.drawable.ic_lock, tone = IconTone.SECONDARY, onClick = onPassword)
        }
        DangerAction(
            label = "退出账户",
            icon = R.drawable.ic_logout,
            onClick = {
                if (state.session != null) snackbar.show("请先结束当前会话，再退出账户。", SnackbarAction("返回使用页面", onSession), error = true)
                else logoutSheet = true
            }
        )
    }

    if (logoutSheet) {
        ConfirmBottomSheet(
            title = "退出账户？",
            description = "退出后需要重新登录。",
            confirm = "退出",
            destructive = true,
            onDismiss = { logoutSheet = false }
        ) {
            logoutSheet = false
            onLogout()
        }
    }
}

@Composable
fun LoginScreen(onSend: suspend (String) -> Boolean = { false }, onLogin: suspend (String, String, Boolean) -> Boolean = { _, _, _ -> false },
    loginRetryAfterMillis: () -> Long = { 0 }) {
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sms by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalHydrosSnackbar.current
    var retrySeconds by remember { mutableIntStateOf(0) }
    val retryAfter by rememberUpdatedState(loginRetryAfterMillis)
    fun refreshCooldown() {
        retrySeconds = ((retryAfter() + 999) / 1_000).toInt()
    }
    LaunchedEffect(Unit) {
        while (true) {
            refreshCooldown()
            kotlinx.coroutines.delay(250)
        }
    }
    PageColumn {
        Column(Modifier.padding(vertical=24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_water, tone=IconTone.PRIMARY)
            Text("欢迎回到 Hydros", style=MaterialTheme.typography.headlineMedium)
            Text("校园用水，从这里开始。", color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentedControl(
            options = listOf("密码登录", "验证码登录"),
            selectedIndex = if (sms) 1 else 0,
            onSelect = { index ->
                sms = index == 1
                if (sms) password = "" else code = ""
                submitted = false
            }
        )
        Section {
            Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                FormField(phone, { phone=it }, "手机号", numeric=true, error=if(submitted) phoneError(phone) else null)
                if(sms) VerificationCodeField(phone, code, { code=it }, if(submitted) codeError(code) else null, validatePhone = ::phoneError, maxLength = null, onSend = onSend)
                else FormField(password, { password=it }, "密码", secret=true, error=if(submitted) passwordError(password) else null)
            }
        }
        PrimaryAction(if(busy) "正在登录" else if(retrySeconds > 0) "${retrySeconds} 秒后重试" else "登录", enabled=!busy && retrySeconds == 0) {
            submitted=true
            val error = phoneError(phone) ?: if (sms) codeError(code) else passwordError(password)
            if (error != null) snackbar.show(error, error = true)
            else scope.launch {
                busy=true
                try { if(onLogin(phone, if(sms) code else password, sms)) { password=""; code="" } }
                finally { refreshCooldown(); busy=false }
            }
        }
    }
}

@Composable
fun AccountFormScreen(form: AccountForm, account: AccountUi, onSend: suspend (String, Int) -> Boolean,
    onSave: suspend (String, String, String, String) -> Boolean) {
    var phone by rememberSaveable { mutableStateOf("") }
    var oldPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sms by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val snackbar = LocalHydrosSnackbar.current
    val targetPhone=if(form==AccountForm.PHONE) phone else account.phone
    PageColumn {
        if(form == AccountForm.PASSWORD) {
            SegmentedControl(
                options = listOf("通过当前密码修改", "通过短信验证码修改"),
                selectedIndex = if (sms) 1 else 0,
                onSelect = { index ->
                    sms = index == 1
                    if (sms) oldPassword = "" else code = ""
                    submitted = false
                }
            )
        }
        Section {
            Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(form==AccountForm.PHONE) {
                    FormField(phone, { phone=it }, "新手机号", numeric=true, error=if(submitted) phoneError(phone) ?: if(phone==account.phone) "请输入不同的新号码" else null else null)
                    VerificationCodeField(phone, code, { code=it }, if(submitted) codeError(code) else null, validatePhone = ::phoneError, maxLength = null) { onSend(it, 5) }
                } else {
                    if (sms) {
                        Text("当前手机号：${account.phone}")
                        VerificationCodeField(targetPhone, code, { code=it }, if(submitted) codeError(code) else null, validatePhone = ::phoneError, maxLength = null) { onSend(it, 2) }
                    } else {
                        FormField(oldPassword, { oldPassword=it }, "当前密码", secret=true, error=if(submitted) passwordError(oldPassword) else null)
                    }
                    FormField(password, { password=it }, "新密码", secret=true, error=if(submitted) passwordError(password) else null)
                    FormField(repeated, { repeated=it }, "确认新密码", secret=true, error=if(submitted && repeated!=password) "两次输入的密码不一致" else null)
                }
            }
        }
        PrimaryAction(if(busy) "正在保存" else "保存修改", enabled=!busy) {
            submitted=true
            val error=when(form) {
                AccountForm.PHONE -> phoneError(phone) ?: if (phone == account.phone) "请输入不同的新号码" else codeError(code)
                AccountForm.PASSWORD -> if (sms) {
                    codeError(code) ?: passwordError(password) ?: if (repeated != password) "两次输入的密码不一致" else null
                } else {
                    passwordError(oldPassword) ?: passwordError(password) ?: if (repeated != password) "两次输入的密码不一致" else null
                }
            }
            if (error != null) snackbar.show(error, error = true)
            else scope.launch {
                busy=true
                try {
                    val saveOld = if (form == AccountForm.PASSWORD && !sms) oldPassword else ""
                    val saveCode = if (form == AccountForm.PHONE || sms) code else ""
                    if(onSave(targetPhone, saveOld, password, saveCode)) {
                        oldPassword=""; password=""; repeated=""; code=""; snackbar.show("修改完成", severity = dev.uednd.hydros.data.feedback.FeedbackSeverity.SUCCESS)
                    }
                }
                finally { busy=false }
            }
        }
    }
}
