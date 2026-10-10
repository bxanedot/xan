import importlib.util
import pathlib
import tempfile
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'android_toolchain.py'
spec = importlib.util.spec_from_file_location('android_toolchain', path)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class AndroidToolchainTests(unittest.TestCase):
    def test_patch_selection_holds_prerelease_and_major_migrations(self):
        self.assertEqual(tool.latest_patch('9.4.1', ['9.4.2', '9.5.0', '10.0.0', '9.4.3-beta01']), '9.4.2')
        self.assertEqual(tool.latest_patch('2.4.20', ['2.4.10', '2.5.0']), '2.4.20')

    def test_unknown_android_api_and_incompatible_gradle_are_rejected(self):
        with self.assertRaises(ValueError):
            tool.validate_profile(dict(agp='9.4.1', gradle='9.5.0', compileSdk=37, targetSdk=36, java=21))
        with self.assertRaises(ValueError):
            tool.validate_profile(dict(agp='9.4.1', gradle='9.6.0', compileSdk=38, targetSdk=36, java=21))
        tool.validate_profile(dict(agp='9.4.1', gradle='9.6.0', compileSdk=37, targetSdk=36, java=21))

    def test_failed_verification_restores_every_file_byte_for_byte(self):
        with tempfile.TemporaryDirectory(prefix='xan-android-test-') as directory:
            root = pathlib.Path(directory)
            files = [root / 'app.gradle', root / 'module.gradle', root / 'wrapper.jar']
            for file in files:
                file.write_bytes(b'original\r\n\x00')
            def fail():
                raise RuntimeError('verification failed')
            with self.assertRaises(RuntimeError):
                with tool.transaction(files, root / 'backup'):
                    for file in files:
                        file.write_bytes(b'changed')
                    fail()
            self.assertTrue(all(file.read_bytes() == b'original\r\n\x00' for file in files))

    def test_updates_compile_sdk_without_changing_min_or_target_sdk(self):
        text = 'compileSdk = 36\nminSdk = 26\ntargetSdk = 36\n'
        self.assertEqual(tool.update_sdk_text(text, 37, None), 'compileSdk = 37\nminSdk = 26\ntargetSdk = 36\n')

    def test_lint_errors_reject_a_successful_gradle_exit(self):
        with tempfile.TemporaryDirectory(prefix='xan-lint-test-') as directory:
            report = pathlib.Path(directory) / 'lint.xml'
            report.write_text('<issues><issue severity="Error"/></issues>')
            with self.assertRaises(RuntimeError):
                tool.require_clean_lint(report)
            report.write_text('<issues><issue severity="Warning"/></issues>')
            tool.require_clean_lint(report)


if __name__ == '__main__':
    unittest.main()
