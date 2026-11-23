package io.github.ocmediasync.ui

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.ocmediasync.account.OpenCloudAccounts
import io.github.ocmediasync.media.MediaRepository

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshEnvironment()
        }

    private val accountLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let(viewModel::onAccountChosen)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppRoot(
                    viewModel = viewModel,
                    actions = object : ScreenActions {
                        override fun requestPermissions() =
                            permissionLauncher.launch(MediaRepository.requiredPermissions())

                        override fun openAppSettings() = startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                        )

                        override fun chooseAccount() =
                            accountLauncher.launch(OpenCloudAccounts.chooseAccountIntent(viewModel.currentAccount()))

                        override fun testConnection() = viewModel.testConnection(this@MainActivity)

                        @SuppressLint("BatteryLife") // Play ストア外での配布のため許可を直接求める
                        override fun requestIgnoreBatteryOptimizations() = startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )

                        override fun openOfficialApp() {
                            OpenCloudAccounts.officialAppIntent(this@MainActivity)?.let(::startActivity)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }
}

/** Activity が必要な操作 */
interface ScreenActions {
    fun requestPermissions()
    fun openAppSettings()
    fun chooseAccount()
    fun testConnection()
    fun requestIgnoreBatteryOptimizations()
    fun openOfficialApp()
}
