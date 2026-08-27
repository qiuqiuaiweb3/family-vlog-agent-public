package com.chill.familyvlog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.chill.familyvlog.input.buildVideoPickerRequest
import com.chill.familyvlog.ui.AppGraph
import com.chill.familyvlog.ui.MainViewModel
import com.chill.familyvlog.ui.VlogScreen
import com.chill.familyvlog.ui.theme.FamilyVlogTuiTheme
import com.chill.familyvlog.ui.theme.TuiBackground
import com.google.firebase.FirebaseApp

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBarStyle = SystemBarStyle.dark(TuiBackground.toArgb())
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        setContent {
            FamilyVlogTuiTheme {
                val mainViewModel: MainViewModel = viewModel {
                    val firebaseConfigured = FirebaseApp.getApps(applicationContext).isNotEmpty()
                    MainViewModel(
                        runtime = if (firebaseConfigured) AppGraph(applicationContext) else null,
                        firebaseConfigured = firebaseConfigured,
                    )
                }
                val state by mainViewModel.uiState.collectAsState()
                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickMultipleVisualMedia(),
                    onResult = mainViewModel::acceptSelection,
                )

                VlogScreen(
                    state = state,
                    onConfirmDisclosure = mainViewModel::confirmDisclosure,
                    onRequestPicker = {
                        if (mainViewModel.uiState.value.canSelectVideos) {
                            picker.launch(buildVideoPickerRequest())
                        }
                    },
                    onCreateVlog = mainViewModel::createVlog,
                    onCancel = mainViewModel::cancel,
                    onOpenResult = { state.finalUri?.let(::openResult) },
                    onAddSubtitles = mainViewModel::createSubtitles,
                    onCancelSubtitles = mainViewModel::cancelSubtitles,
                    onOpenSubtitledResult = { state.subtitledUri?.let(::openResult) },
                    onOpenTranslationInfo = {
                        openWebPage(Uri.parse("https://translate.google.com"))
                    },
                )
            }
        }
    }

    private fun openResult(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun openWebPage(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
