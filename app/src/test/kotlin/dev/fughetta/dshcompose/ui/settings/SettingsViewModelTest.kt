package dev.fughetta.dshcompose.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.settings.Settings
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = TestRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        ViewModelProvider(
            store,
            viewModelFactory { initializer { SettingsViewModel(repository) } },
        )[SettingsViewModel::class.java]

    @Test
    fun failedWriteKeepsPersistedSettingsAndReportsError() =
        runTest(dispatcher) {
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            runCurrent()
            val original = repository.data.value
            repository.writeTheme = { throw IOException("write failed") }
            vm.theme(ThemeMode.Dark)
            runCurrent()
            assertEquals(original, vm.uiState.value.settings)
            assertEquals(1L, vm.uiState.value.errorRevision)
        }

    @Test
    fun ownerClearingCancelsWritesAndStopsObservation() =
        runTest(dispatcher) {
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            runCurrent()
            var cancelled = false
            repository.writeTheme = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            vm.theme(ThemeMode.Dark)
            runCurrent()
            store.clear()
            runCurrent()
            assertTrue(cancelled)
            val lastState = vm.uiState.value
            repository.data.value = repository.data.value.copy(dynamicColor = false)
            runCurrent()
            assertEquals(lastState, vm.uiState.value)
        }

    private class TestRepository : dev.fughetta.dshcompose.testing.FakeSettingsRepository() {
        override val data = MutableStateFlow(Settings(ThemeMode.System, true, false, true))
        var writeTheme: suspend (ThemeMode) -> Unit = { data.value = data.value.copy(theme = it) }

        override suspend fun theme(value: ThemeMode) = writeTheme(value)
    }
}
