package app.aaps.ui.compose.smartMealDialog.food

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Opens the camera or the photo gallery for a meal photo. */
internal class MealPhotoLauncher(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * A launcher for meal photos, or null on a platform that has none (the photo buttons are then not
 * shown). [onPhoto] gets a JPEG made small enough to send.
 */
@Composable
internal expect fun rememberMealPhotoLauncher(onPhoto: (ByteArray) -> Unit): MealPhotoLauncher?

/** Whether [BarcodeScanner] works on this platform. Without it the scan button is not shown. */
internal expect val barcodeScannerAvailable: Boolean

/** A camera view that reports the first food barcode (EAN or UPC) it reads. Asks for the camera first. */
@Composable
internal expect fun BarcodeScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier)
