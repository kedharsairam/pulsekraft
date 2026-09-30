package com.krafttools.pulsekraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krafttools.pulsekraft.ui.MeasureScreen
import com.krafttools.pulsekraft.ui.theme.PulseKraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PulseKraftTheme {
                val model: MeasureViewModel = viewModel()
                Scaffold { padding ->
                    MeasureScreen(model, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}
