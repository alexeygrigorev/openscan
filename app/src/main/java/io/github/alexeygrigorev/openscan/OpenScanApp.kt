package io.github.alexeygrigorev.openscan

import android.app.Application
import io.github.alexeygrigorev.openscan.data.AppContainer

class OpenScanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
