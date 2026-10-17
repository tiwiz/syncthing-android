package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme

/** Scans a QR code with the camera and returns its text as [QR_RESULT_ARG]. */
class QRScannerActivity : ThemedAppCompatActivity(), BarcodeCallback {

    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barcodeView = DecoratedBarcodeView(this)
        setContent {
            ApplicationTheme {
                QrScannerScreen(
                    barcodeView = barcodeView,
                    onCancel = {
                        pauseScanner()
                        finish()
                    },
                )
            }
        }
        checkPermissionAndStartScanner()
    }

    override fun onResume() {
        checkPermissionAndStartScanner()
        super.onResume()
    }

    override fun onStop() {
        pauseScanner()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == RC_HANDLE_CAMERA_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner()
            } else {
                finish()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun barcodeResult(result: BarcodeResult) {
        setResult(Activity.RESULT_OK, Intent().putExtra(QR_RESULT_ARG, result.text))
        finish()
    }

    override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
        // Unused
    }

    private fun checkPermissionAndStartScanner() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                RC_HANDLE_CAMERA_PERM,
            )
        } else {
            startScanner()
        }
    }

    private fun startScanner() {
        barcodeView.resume()
        barcodeView.decodeSingle(this)
    }

    private fun pauseScanner() {
        barcodeView.pause()
    }

    companion object {
        const val QR_RESULT_ARG = "QR_CODE"
        private const val RC_HANDLE_CAMERA_PERM = 888

        @JvmStatic
        fun intent(context: Context): Intent = Intent(context, QRScannerActivity::class.java)
    }
}

/** The camera preview of [barcodeView] above a full-width cancel bar. */
@Composable
private fun QrScannerScreen(
    barcodeView: DecoratedBarcodeView,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding()
                    .height(CANCEL_BAR_HEIGHT.dp)
                    .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.cancel_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val CANCEL_BAR_HEIGHT = 44
