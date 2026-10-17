package dev.uednd.hydros.ui.components

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns send progress and the cooldown; callers provide validation and the real send result. */
@Composable
fun VerificationCodeField(
    phone: String,
    value: String,
    onValue: (String) -> Unit,
    error: String?,
    validatePhone: (String) -> String?,
    maxLength: Int? = null,
    onSend: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var until by rememberSaveable { mutableLongStateOf(0) }
    var remaining by remember { mutableIntStateOf(0) }
    val snackbar = LocalHydrosSnackbar.current
    LaunchedEffect(until, phone) {
        while (until > SystemClock.elapsedRealtime()) {
            remaining = ((until - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
            delay(1000)
        }
        remaining = 0
    }
    FormField(
        value = value,
        onValue = onValue,
        label = "验证码",
        numeric = true,
        error = error,
        maxLength = maxLength,
        trailing = {
            TextButton(
                onClick = {
                    val phoneMessage = validatePhone(phone)
                    if (phoneMessage != null) snackbar.show(phoneMessage, error = true)
                    else scope.launch {
                        sending = true
                        try {
                            if (onSend(phone)) {
                                until = SystemClock.elapsedRealtime() + 60_000
                                snackbar.show("验证码已发送", severity = dev.uednd.hydros.data.feedback.FeedbackSeverity.SUCCESS)
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = !sending && remaining == 0
            ) {
                Text(
                    text = when {
                        sending -> "正在发送"
                        remaining > 0 -> "${remaining} 秒后重新获取"
                        else -> "获取验证码"
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}
