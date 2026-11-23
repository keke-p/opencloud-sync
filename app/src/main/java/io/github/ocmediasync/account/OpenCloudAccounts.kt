package io.github.ocmediasync.account

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 認証にユーザー操作 (公式アプリでのログイン、アクセス許可) が必要な状態 */
class AuthRequiredException(message: String, val resolution: Intent? = null) : Exception(message)

/** トークンの取得・失効を行うもの。OkHttp のスレッドからブロッキングで呼ばれる。 */
interface TokenSource {
    fun token(): String
    fun invalidate(token: String)
}

/**
 * 公式 OpenCloud アプリが AccountManager に登録したアカウントを利用する。
 * アクセストークンのリフレッシュは公式アプリの Authenticator が行う。
 */
object OpenCloudAccounts {
    const val ACCOUNT_TYPE = "eu.opencloud"
    const val TOKEN_TYPE = "eu.opencloud.oauth2.access_token"
    const val OFFICIAL_PACKAGE = "eu.opencloud.android"

    /** アカウント選択画面。ここで選ばれたアカウントは本アプリから見えるようになる。 */
    fun chooseAccountIntent(current: Account?): Intent =
        AccountManager.newChooseAccountIntent(
            current, null, arrayOf(ACCOUNT_TYPE), null, null, null, null,
        )

    fun find(context: Context, name: String?): Account? {
        if (name == null) return null
        return AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull { it.name == name }
    }

    fun isOfficialAppInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(OFFICIAL_PACKAGE) != null

    fun officialAppIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(OFFICIAL_PACKAGE)

    /**
     * バックグラウンド用。ユーザー操作が必要な場合は [AuthRequiredException]、
     * 公式アプリとの通信に失敗した場合は IOException。
     * メインスレッドから呼んではいけない。
     */
    fun backgroundTokenSource(context: Context, account: Account): TokenSource =
        AccountTokenSource(AccountManager.get(context), account, activity = null)

    /**
     * 画面用。アクセス許可ダイアログや公式アプリのログイン画面を必要に応じて表示する。
     * メインスレッドから呼んではいけない。
     */
    fun interactiveTokenSource(activity: Activity, account: Account): TokenSource =
        AccountTokenSource(AccountManager.get(activity), account, activity)

    private class AccountTokenSource(
        private val manager: AccountManager,
        private val account: Account,
        private val activity: Activity?,
    ) : TokenSource {

        override fun token(): String {
            val first = request()
            first.getString(AccountManager.KEY_AUTHTOKEN)?.let { return it }
            // 画面から呼んだ場合、OS のアクセス許可ダイアログで許可されると
            // トークンなしの結果が返るので、もう一度取得し直す
            val result = if (activity != null && !first.containsKey(AccountManager.KEY_INTENT)) request() else first

            result.getString(AccountManager.KEY_AUTHTOKEN)?.let { return it }

            @Suppress("DEPRECATION")
            val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)
            throw AuthRequiredException(
                "アカウントへのアクセス許可、または公式アプリでの再ログインが必要です",
                intent,
            )
        }

        private fun request(): Bundle =
            try {
                val future = if (activity != null) {
                    manager.getAuthToken(account, TOKEN_TYPE, null, activity, null, null)
                } else {
                    manager.getAuthToken(account, TOKEN_TYPE, null, false, null, null)
                }
                future.result
            } catch (e: OperationCanceledException) {
                throw AuthRequiredException("アカウントへのアクセスがキャンセルされました")
            } catch (e: AuthenticatorException) {
                throw AuthRequiredException("公式アプリでの認証に失敗しました: ${e.message}")
            } catch (e: SecurityException) {
                throw AuthRequiredException("アカウントが見つかりません。アカウントを選択し直してください")
            }

        override fun invalidate(token: String) {
            manager.invalidateAuthToken(ACCOUNT_TYPE, token)
        }
    }
}
