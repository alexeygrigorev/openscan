package io.github.alexeygrigorev.scanlet

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
import io.github.alexeygrigorev.scanlet.data.AppContainer
import io.github.alexeygrigorev.scanlet.ui.CaptureScreen
import io.github.alexeygrigorev.scanlet.ui.CaptureViewModel
import io.github.alexeygrigorev.scanlet.ui.DocumentScreen
import io.github.alexeygrigorev.scanlet.ui.DocumentViewModel
import io.github.alexeygrigorev.scanlet.ui.DocumentsScreen
import io.github.alexeygrigorev.scanlet.ui.DocumentsViewModel
import io.github.alexeygrigorev.scanlet.ui.EditScreen
import io.github.alexeygrigorev.scanlet.ui.EditViewModel
import io.github.alexeygrigorev.scanlet.ui.theme.ScanletTheme

object Routes {
    const val DOCUMENTS = "documents"
    const val CAPTURE = "capture"
    const val DOCUMENT = "document/{documentId}"
    const val EDIT = "edit/{pageId}"

    fun document(id: Long) = "document/$id"
    fun edit(id: Long) = "edit/$id"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ScanletApp).container
        setContent {
            ScanletTheme {
                ScanletNavHost(container)
            }
        }
    }
}

@Composable
fun ScanletNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    NavHost(navController = navController, startDestination = Routes.DOCUMENTS) {

        composable(Routes.DOCUMENTS) {
            DocumentsScreen(
                viewModel = viewModel { DocumentsViewModel(container.repository) },
                onOpenDocument = { id -> navController.navigate(Routes.document(id)) },
                onScan = { navController.navigate(Routes.CAPTURE) },
            )
        }

        composable(Routes.CAPTURE) {
            CaptureScreen(
                viewModel = viewModel { CaptureViewModel(container.repository) },
                onDone = { documentId ->
                    navController.navigate(Routes.document(documentId)) {
                        popUpTo(Routes.DOCUMENTS)
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
