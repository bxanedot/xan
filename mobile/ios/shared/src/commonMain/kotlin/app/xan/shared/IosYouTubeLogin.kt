package app.xan.shared

/** Callback implemented by the shared UI when the native sign-in view finishes. */
interface IosYouTubeLoginCallback {
    fun onLoginCookie(cookie: String, dataSyncId: String?)
    fun onLoginCancelled()
    fun onLoginError(message: String)
}

/** Native host hook; iOS implements this with a WKWebView and Keychain-backed flow. */
interface IosYouTubeLoginPresenter {
    fun presentLogin(callback: IosYouTubeLoginCallback)
}

object IosYouTubeLogin {
    private var presenter: IosYouTubeLoginPresenter? = null

    fun install(presenter: IosYouTubeLoginPresenter?) {
        this.presenter = presenter
    }

    fun open(callback: IosYouTubeLoginCallback) {
        val installed = presenter
        if (installed == null) {
            callback.onLoginError("The native YouTube sign-in view is unavailable. Paste the saved cookie instead.")
            return
        }
        runCatching { installed.presentLogin(callback) }
            .onFailure { callback.onLoginError(it.message ?: "Could not open YouTube Music sign-in.") }
    }
}
