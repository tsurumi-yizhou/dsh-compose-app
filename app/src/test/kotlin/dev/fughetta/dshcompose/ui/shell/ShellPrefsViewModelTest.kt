package dev.fughetta.dshcompose.ui.shell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.settings.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellPrefsViewModelTest {
    private class Repository : dev.fughetta.dshcompose.testing.FakeSettingsRepository() {
        val values = MutableStateFlow(ShellPreferences())
        override val shell = values.asStateFlow()
        var fail = false
        var items = emptyList<String>()

        override suspend fun quickPanelSwipe(enabled: Boolean) {
            if (fail) throw IOException("write failed")
            values.value = values.value.copy(quickPanelSwipe = enabled)
        }

        override suspend fun quickPanelItems(items: List<String>) {
            this.items = items
            values.value = values.value.copy(quickPanelItems = items)
        }

        override suspend fun silenceRisk(id: String) {}

        override suspend fun rememberSearch(query: String) {}

        override suspend fun clearSearches() {}

        override suspend fun backgroundGuideSeen() {}
    }

    @Test
    fun lifecycleSubscriptionsAndValidatedActionsBelongToTheActivityOwnedViewModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = Repository()
            val provider =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { ShellPrefsViewModel(repository) } },
                )
            val model = provider[ShellPrefsViewModel::class.java]
            assertSame(model, provider[ShellPrefsViewModel::class.java])
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    model.uiState.collect()
                }
            runCurrent()
            assertEquals(1, repository.values.subscriptionCount.value)
            model.onAction(
                ShellPrefsAction.QuickPanelItems(
                    listOf("settings", "missing", "plugins", "settings"),
                ),
            )
            runCurrent()
            assertEquals(listOf("settings", "plugins"), repository.items)
            assertEquals(repository.items, model.uiState.value.preferences?.quickPanelItems)
            repository.fail = true
            model.onAction(ShellPrefsAction.QuickPanelSwipe(false))
            runCurrent()
            assertEquals(1L, model.uiState.value.errorRevision)
            assertTrue(model.uiState.value.preferences!!.quickPanelSwipe)
            collector.cancel()
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(0, repository.values.subscriptionCount.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
