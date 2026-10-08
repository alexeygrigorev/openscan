package io.github.alexeygrigorev.openscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.alexeygrigorev.openscan.data.AppContainer
import io.github.alexeygrigorev.openscan.ui.BatchImport
import io.github.alexeygrigorev.openscan.ui.CaptureScreen
import io.github.alexeygrigorev.openscan.ui.CaptureViewModel
import io.github.alexeygrigorev.openscan.ui.DocumentScreen
import io.github.alexeygrigorev.openscan.ui.DocumentViewModel
import io.github.alexeygrigorev.openscan.ui.DocumentsScreen
import io.github.alexeygrigorev.openscan.ui.DocumentsViewModel
import io.github.alexeygrigorev.openscan.ui.EditScreen
import io.github.alexeygrigorev.openscan.ui.EditViewModel
import io.github.alexeygrigorev.openscan.ui.SettingsScreen
import io.github.alexeygrigorev.openscan.ui.SettingsViewModel
import io.github.alexeygrigorev.openscan.ui.theme.OpenScanTheme

object Routes {
    const val DOCUMENTS = "documents"
    const val CAPTURE = "capture?documentId={documentId}&gallery={gallery}"
    const val DOCUMENT = "document/{documentId}"
    const val EDIT = "edit/{pageId}"
    const val SETTINGS = "settings"

    fun document(id: Long) = "document/$id"
    fun edit(id: Long) = "edit/$id"

    /**
     * Without [documentId] the capture flow creates a new document; with one
     * it appends. [gallery] skips the scanner and opens the photo picker
     * straight away (the library's Import images entry). Absent arguments are
     * left out of the URI entirely so their nav defaults apply.
     */
    fun capture(documentId: Long? = null, gallery: Boolean = false): String =
        buildString {
            append("capture?gallery=$gallery")
            if (documentId != null) append("&documentId=$documentId")
        }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as OpenScanApp).container
        // One update check per launch; the banner and the settings screen both
        // observe the same app-scoped answer.
        container.updateMonitor.checkAtLaunch()
        setContent {
            OpenScanTheme {
                OpenScanNavHost(container)
            }
        }
    }
}

@Composable
fun OpenScanNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    NavHost(navController = navController, startDestination = Routes.DOCUMENTS) {

        composable(Routes.DOCUMENTS) {
            DocumentsScreen(
                viewModel = viewModel { DocumentsViewModel(container.repository) },
                updateMonitor = container.updateMonitor,
                onOpenDocument = { id -> navController.navigate(Routes.document(id)) },
                onScan = { navController.navigate(Routes.capture()) },
                onImportImages = { navController.navigate(Routes.capture(gallery = true)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel { SettingsViewModel(container.settings) },
                updateMonitor = container.updateMonitor,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.CAPTURE,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("gallery") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            // -1 (the default) means "create a new document"; a real id means
            // the scanned pages are appended to that existing document.
            val appendTo = entry.arguments?.getLong("documentId", -1L)?.takeIf { it > 0 }
            CaptureScreen(
                viewModel = viewModel { CaptureViewModel(container.repository) },
                documentId = appendTo,
                galleryOnly = entry.arguments?.getBoolean("gallery", false) ?: false,
                onDone = { documentId, added, failed ->
                    BatchImport.summary.value = added to failed
                    if (appendTo != null) {
                        // The document screen sits right below in the back
                        // stack and picks the new pages up from its own flow.
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.document(documentId)) {
                            popUpTo(Routes.DOCUMENTS)
                        }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            Routes.DOCUMENT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val documentId = entry.arguments?.getLong("documentId") ?: return@composable
            DocumentScreen(
                viewModel = viewModel(key = "document-$documentId") {
                    DocumentViewModel(documentId, container.repository, container.files, appContext)
                },
                onEditPage = { pageId -> navController.navigate(Routes.edit(pageId)) },
                onAddPages = { navController.navigate(Routes.capture(documentId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("pageId") { type = NavType.LongType }),
        ) { entry ->
            val pageId = entry.arguments?.getLong("pageId") ?: return@composable
            EditScreen(
                viewModel = viewModel(key = "edit-$pageId") {
                    EditViewModel(pageId, container.repository, appContext)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
