import Foundation
import CoreFoundation
import Security
import CryptoKit
import UIKit
import XanShared

final class KeychainRoomSessionStore: NSObject, RoomSessionStore, YouTubeCredentialStore, IosLastFmSecretStore, IosListenBrainzTokenStore, IosLyricsTranslationKeyStore, IosLyricsRomanizer {
    private let service = "app.xan.ios.listen-together"
    private let account = "room-session"
    private let youtubeCookieService = "app.xan.ios.youtube-music"
    private let youtubeCookieAccount = "session-cookie"
    private let lastFmService = "app.xan.ios.lastfm"
    private let listenBrainzService = "app.xan.ios.listenbrainz"
    private let lyricsTranslationService = "app.xan.ios.lyrics-translation"

    func load() -> SavedRoomSession? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let payload = try? JSONDecoder().decode(Session.self, from: data) else { return nil }
        return SavedRoomSession(sessionToken: payload.token, roomCode: payload.roomCode, serverUrl: payload.serverUrl, username: payload.username)
    }

    func save(session: SavedRoomSession?) {
        guard let session else {
            SecItemDelete(baseQuery() as CFDictionary)
            return
        }
        let payload = Session(token: session.sessionToken, roomCode: session.roomCode, serverUrl: session.serverUrl, username: session.username)
        guard let data = try? JSONEncoder().encode(payload) else { return }
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(baseQuery() as CFDictionary, [kSecValueData as String: data] as CFDictionary)
            if updateStatus != errSecSuccess {
                NSLog("XAN could not update the saved room token in Keychain (status %d).", updateStatus)
            }
        } else if status != errSecSuccess {
            NSLog("XAN could not save the room token in Keychain (status %d).", status)
        }
    }

    func loadCookie() -> String? {
        var query = youtubeCookieQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveCookie(cookie: String?) -> Bool {
        guard let cookie, !cookie.isEmpty else {
            let status = SecItemDelete(youtubeCookieQuery() as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        var query = youtubeCookieQuery()
        query[kSecValueData as String] = Data(cookie.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(youtubeCookieQuery() as CFDictionary, [kSecValueData as String: Data(cookie.utf8)] as CFDictionary)
            return updateStatus == errSecSuccess
        }
        return status == errSecSuccess
    }

    func loadLastFmSecret(name: String) -> String? {
        guard let query = lastFmQuery(name: name) else { return nil }
        var readQuery = query
        readQuery[kSecReturnData as String] = true
        readQuery[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(readQuery as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveLastFmSecret(name: String, value: String?) -> Bool {
        guard let query = lastFmQuery(name: name) else { return false }
        guard let value, !value.isEmpty else {
            let status = SecItemDelete(query as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        var writeQuery = query
        writeQuery[kSecValueData as String] = Data(value.utf8)
        writeQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(writeQuery as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData as String: Data(value.utf8)] as CFDictionary)
            return updateStatus == errSecSuccess
        }
        return status == errSecSuccess
    }

    func md5Hex(value: String) -> String {
        Insecure.MD5.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func hmacSha256Base64(value: String, key: String) -> String {
        let signingKey = SymmetricKey(data: Data(key.utf8))
        let digest = HMAC<SHA256>.authenticationCode(for: Data(value.utf8), using: signingKey)
        return Data(digest).base64EncodedString()
    }

    func openLastFmAuthorizationUrl(url raw: String) -> Bool {
        guard let url = URL(string: raw), url.scheme == "https", url.host == "www.last.fm", url.path == "/api/auth/" else {
            return false
        }
        UIApplication.shared.open(url)
        return true
    }

    func currentUnixTimeSeconds() -> Int64 {
        Int64(Date().timeIntervalSince1970)
    }

    func loadListenBrainzToken() -> String? {
        var query = listenBrainzQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveListenBrainzToken(token: String?) -> Bool {
        guard let token, !token.isEmpty else {
            let status = SecItemDelete(listenBrainzQuery() as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        var query = listenBrainzQuery()
        query[kSecValueData as String] = Data(token.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(listenBrainzQuery() as CFDictionary, [kSecValueData as String: Data(token.utf8)] as CFDictionary)
            return updateStatus == errSecSuccess
        }
        return status == errSecSuccess
    }

    func loadLyricsTranslationKey() -> String? {
        var query = lyricsTranslationQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveLyricsTranslationKey(key: String?) -> Bool {
        guard let key, !key.isEmpty else {
            let status = SecItemDelete(lyricsTranslationQuery() as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        var query = lyricsTranslationQuery()
        query[kSecValueData as String] = Data(key.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(lyricsTranslationQuery() as CFDictionary, [kSecValueData as String: Data(key.utf8)] as CFDictionary)
            return updateStatus == errSecSuccess
        }
        return status == errSecSuccess
    }

    func romanizeLyrics(text: String) -> String? {
        guard text.count <= 2_000 else { return nil }
        let mutable = NSMutableString(string: text)
        guard CFStringTransform(mutable, nil, kCFStringTransformToLatin, false) else { return nil }
        _ = CFStringTransform(mutable, nil, kCFStringTransformStripCombiningMarks, false)
        let result = mutable as String
        return result == text ? nil : result
    }

    private func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func youtubeCookieQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: youtubeCookieService, kSecAttrAccount as String: youtubeCookieAccount]
    }

    private func lastFmQuery(name: String) -> [String: Any]? {
        let allowed = ["api-key", "api-secret", "session-key", "pending-auth-token"]
        guard allowed.contains(name) else { return nil }
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: lastFmService, kSecAttrAccount as String: name]
    }

    private func listenBrainzQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: listenBrainzService, kSecAttrAccount as String: "user-token"]
    }

    private func lyricsTranslationQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: lyricsTranslationService, kSecAttrAccount as String: "api-key"]
    }

    private struct Session: Codable {
        let token: String
        let roomCode: String
        let serverUrl: String
        let username: String
    }
}
