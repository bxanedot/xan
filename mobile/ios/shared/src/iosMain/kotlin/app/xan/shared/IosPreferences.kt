package app.xan.shared

import platform.Foundation.NSUserDefaults

actual fun readIosPreference(key: String): String? =
    NSUserDefaults.standardUserDefaults.stringForKey("xan.$key")

actual fun writeIosPreference(key: String, value: String) {
    NSUserDefaults.standardUserDefaults.setObject(value, forKey = "xan.$key")
}
