package app.aaps.ui.compose.smartMealDialog.food

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// No camera or gallery here yet: the photo and scan buttons are not shown, and a meal can still be described in text.

@Composable
internal actual fun rememberMealPhotoLauncher(onPhoto: (ByteArray) -> Unit): MealPhotoLauncher? = null

internal actual val barcodeScannerAvailable: Boolean = false

@Composable
internal actual fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier) = Unit
