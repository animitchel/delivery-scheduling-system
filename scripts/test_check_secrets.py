import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check_secrets', Path(__file__).with_name('check-secrets.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SecretChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = module.scanner(module.ROOT)

    def test_real_scanner_current_history_and_exact_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'security').mkdir()
            (root / 'security/gitleaks.toml').write_text('[extend]\nuseDefault = true\n')
            policy = root / 'security/secret-exceptions.json'
            policy.write_text('{"exceptions": []}')
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            git('init'); git('config', 'user.email', 'test@example.invalid'); git('config', 'user.name', 'Test')
            # Synthetic, non-provider credential; assemble so this fixture is not itself a finding.
            token = 'AIza' + ''.join(chr(65 + i % 26) if i % 2 else str(i % 10) for i in range(35))
            (root / 'example.txt').write_text('key=' + token + ' # gitleaks:allow')
            git('add', '.'); git('commit', '-m', 'fixture')
            result = module.check(root, self.binary)
            self.assertFalse(result['passed'])
            self.assertEqual(len(result['current']), 1)
            self.assertEqual(len(result['history']), 1)
            self.assertNotIn(token, json.dumps(result))
            policy.write_text(json.dumps({'exceptions': [{'fingerprint': result['history'][0]['fingerprint'], 'reason': 'Synthetic fixture'}]}))
            self.assertFalse(module.check(root, self.binary)['passed'])
            (root / 'example.txt').write_text('safe example')
            git('add', '.'); git('commit', '-m', 'remove fixture')
            (root / '.gitignore').write_text('private.env\n')
            (root / 'private.env').write_text('key=' + token)
            git('add', '.gitignore')
            self.assertTrue(module.check(root, self.binary)['passed'])
            (root / 'example.txt').write_text('key=' + token + ' # gitleaks:allow')
            git('add', '.'); git('commit', '-m', 'new leak')
            self.assertFalse(module.check(root, self.binary)['passed'])

    def test_tracked_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init'], cwd=root, capture_output=True, check=True)
            (root / 'security').mkdir()
            (root / 'security/secret-exceptions.json').write_text('{"exceptions": []}')
            (root / 'private').write_text('private configuration')
            (root / 'linked').symlink_to(root / 'private')
            subprocess.run(['git', 'add', 'linked'], cwd=root, capture_output=True, check=True)
            with self.assertRaises(ValueError):
                module.check(root, self.binary)

    def test_scanner_errors_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            (tmp / 'ignore').write_text('')
            with self.assertRaises(ValueError):
                module.scan('/usr/bin/false', module.ROOT, tmp, 'dir', tmp)

    def test_duplicate_or_unexplained_exceptions_rejected(self):
        with self.assertRaises(ValueError):
            module.classify([], [{'fingerprint': 'x', 'reason': ''}], True)
        with self.assertRaises(ValueError):
            module.classify([], [{'fingerprint': 'x', 'reason': 'a'}] * 2, True)

    def test_shallow_checkout_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init'], cwd=root, capture_output=True, check=True)
            (root / '.git/shallow').write_text('a' * 40 + '\n')
            with self.assertRaises(ValueError):
                module.check(root, self.binary)


if __name__ == '__main__':
    unittest.main()
