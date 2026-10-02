package io.github.alexeygrigorev.scanlet

import android.app.Application
import io.github.alexeygrigorev.scanlet.data.AppContainer

class ScanletApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
