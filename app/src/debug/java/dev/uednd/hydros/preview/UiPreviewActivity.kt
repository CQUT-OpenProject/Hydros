package dev.uednd.hydros.preview

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import dev.uednd.hydros.ui.HydrosApp
import dev.uednd.hydros.ui.state.HydrosViewModel
import dev.uednd.hydros.ui.state.AppInfoViewModel
import dev.uednd.hydros.data.update.AppUpdateSource
import kotlinx.coroutines.cancel

/** A separate debug launcher with local fixtures; normal login and storage stay independent. */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dependencies = ViewModelProvider(this)[UiPreviewDependencies::class.java].graph
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HydrosViewModel(dependencies) as T
        })[HydrosViewModel::class.java]
        val appInfo = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppInfoViewModel(AppUpdateSource { null }) as T
        })[AppInfoViewModel::class.java]
        setContent { HydrosApp(model, appInfo) }
    }
}

class UiPreviewDependencies(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {
    val graph = createUiPreviewDependencies(application, (state.get<Int>("billCount") ?: 2).coerceIn(2, 2_000))
    override fun onCleared() { graph.scope.cancel() }
}
