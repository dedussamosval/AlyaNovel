package org.dedussamosval.alya.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.dedussamosval.alya.App
import org.dedussamosval.alya.AppContainer

class VmFactory(private val container: AppContainer, private val build: (AppContainer) -> ViewModel) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
}

/** ViewModel, привязанная к текущему владельцу (экран навигации). key нужен, если на одном владельце несколько экземпляров. */
@Composable
inline fun <reified T : ViewModel> appViewModel(key: String? = null, noinline build: (AppContainer) -> T): T {
    val container = (LocalContext.current.applicationContext as App).container
    return viewModel<T>(key = key, factory = VmFactory(container, build))
}
