package io.github.ocmediasync.account

/**
 * 公式アプリのアカウント名 (`<username>@<host>[:port][/path]`) を分解したもの。
 *
 * 公式アプリが保存しているサーバーURL (userData) は他アプリから読めないため、
 * アカウント名から推定する。スキームはアカウント名に含まれないので https を仮定する。
 */
data class AccountName(val username: String, val serverUrl: String) {
    companion object {
        fun parse(accountName: String): AccountName? {
            // ユーザー名にメールアドレスを使う場合があるため、最後の '@' で区切る
            val at = accountName.lastIndexOf('@')
            if (at <= 0 || at == accountName.length - 1) return null
            val username = accountName.substring(0, at)
            val hostAndPath = accountName.substring(at + 1).trimEnd('/')
            if (hostAndPath.isEmpty()) return null
            return AccountName(username, "https://$hostAndPath")
        }
    }
}
