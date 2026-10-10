import sys
import unittest
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_android_platform_parity as parity


class AndroidPlatformParityTest(unittest.TestCase):
    def test_android_desktop_and_ios_logo_catalogs_match(self):
        catalogs = parity.logo_catalogs()
        self.assertEqual(catalogs["Android"], catalogs["Desktop"])
        self.assertEqual(catalogs["Android"], catalogs["iOS"])
        self.assertEqual([], parity.validate_logo_catalogs())

    def test_desktop_and_ios_logo_resources_match_android_sources(self):
        for variant, source_path in parity.ANDROID_LOGOS.items():
            source = (ROOT / source_path).read_bytes()
            for directory in (
                ROOT / "desktop/shared/src/main/resources/brand",
                ROOT / "mobile/ios/shared/src/commonMain/composeResources/drawable",
            ):
                target = directory / f"xan_logo_{variant}.png"
                self.assertEqual(
                    hashlib.sha256(source).digest(),
                    hashlib.sha256(target.read_bytes()).digest(),
                    f"{target.relative_to(ROOT)} is stale; run the Android logo sync.",
                )

    def test_ios_app_icon_source_resolves_to_android_gold_logo(self):
        source = (ROOT / "mobile/ios/generate-app-icon.swift").read_text(encoding="utf-8")
        relative_path = source.split('root.appendingPathComponent("', 1)[1].split('"', 1)[0]
        self.assertEqual(
            ROOT / "mobile/android/app/src/main/res/drawable-nodpi/xan_gold_mark.png",
            (ROOT / "mobile/ios" / relative_path).resolve(),
        )

    def test_logo_parser_uses_preference_keys(self):
        source = '''enum class Logos {\n    WHITE("white", "White"),\n    GOLD("gold", "Gold");\n}'''
        self.assertEqual([("WHITE", "white"), ("GOLD", "gold")], parity.parse_logo_entries(source))

    def test_android_generated_build_files_are_ignored(self):
        self.assertFalse(parity.is_relevant_android_path("mobile/android/app/build/outputs/app.apk"))
        self.assertFalse(parity.is_relevant_android_path("mobile/android/.gradle/cache.bin"))
        self.assertTrue(parity.is_relevant_android_path("mobile/android/app/src/main/AndroidManifest.xml"))

    def test_android_sources_are_grouped_for_the_parity_report(self):
        groups = parity.classify_android_paths(
            [
                "mobile/android/app/src/main/kotlin/app/xan/music/ui/Home.kt",
                "mobile/android/modules/lyrics/provider/src/main/kotlin/Provider.kt",
                "mobile/android/app/src/main/res/drawable/xan.png",
                "mobile/android/version.properties",
                "mobile/android/app/src/main/kotlin/app/xan/music/listentogether/Protocol.kt",
            ]
        )
        self.assertEqual(1, len(groups["Application behavior and UI"]))
        self.assertEqual(1, len(groups["Provider modules and integrations"]))
        self.assertEqual(1, len(groups["Branding and app resources"]))
        self.assertEqual(1, len(groups["Build, dependencies, and release metadata"]))
        self.assertEqual(1, len(groups["Listen Together protocol and services"]))

    def test_report_flags_missing_platform_work_for_android_behavior_changes(self):
        report = parity.parity_report(
            ["mobile/android/app/src/main/kotlin/app/xan/music/ui/Home.kt"],
            "base",
            "head",
            "pull_request",
        )
        self.assertIn("Parity review", report)
        self.assertIn("desktop", report)
        self.assertIn("iOS/iPadOS", report)

    def test_report_does_not_flag_asset_only_syncs_as_behavior_changes(self):
        report = parity.parity_report(
            ["mobile/android/app/src/main/res/drawable-nodpi/xan_gold_mark.png"],
            "base",
            "head",
            "pull_request",
        )
        self.assertNotIn("Parity review", report)


if __name__ == "__main__":
    unittest.main()
