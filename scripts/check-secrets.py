#!/usr/bin/env python3
"""Pinned tracked-file/history scan; retain locations only, never scanner logs."""
import hashlib
import io
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def run(args, cwd):
    return subprocess.run(args, cwd=cwd, capture_output=True, timeout=180)


def scanner(root):
    spec = json.loads((root / 'security/secret-scanner.json').read_text())
    machine = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'x64'}.get(platform.machine())
    system = platform.system().lower()
    asset = f"gitleaks_{spec['version']}_{system}_{machine}.tar.gz"
    expected = spec['checksums'][asset]
    cache = root / 'target/secret-scanner'
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / asset
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        url = f"https://github.com/gitleaks/gitleaks/releases/download/v{spec['version']}/{asset}"
        data = urllib.request.urlopen(url, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Scanner archive checksum mismatch')
        archive.write_bytes(data)
    # Re-extract the verified binary on every invocation; never trust a cached executable.
    with tarfile.open(fileobj=io.BytesIO(archive.read_bytes()), mode='r:gz') as tar:
        member = tar.getmember('gitleaks')
        if not member.isfile():
            raise ValueError('Invalid scanner binary')
        binary = cache / 'gitleaks'
        binary.write_bytes(tar.extractfile(member).read())
        binary.chmod(0o700)
    return binary


def classify(rows, exceptions, historical):
    allowed = {x['fingerprint'] for x in exceptions}
    if len(allowed) != len(exceptions) or any(not x.get('reason') for x in exceptions):
        raise ValueError('Invalid secret exception policy')
    return [dict(row, acknowledged=historical and row['fingerprint'] in allowed) for row in rows]


def scan(binary, root, target, mode, tmp):
    report = tmp / f'{mode}.json'
    args = [str(binary), mode, str(target), '--config', str(root / 'security/gitleaks.toml'),
            '--redact=100', '--no-banner', '--no-color', '--log-level=error',
            '--ignore-gitleaks-allow', '--gitleaks-ignore-path', str(tmp / 'ignore'),
            '--report-format=json', '--report-path', str(report), '--timeout=120', '--exit-code=10']
    if mode == 'git':
        args.append('--log-opts=--full-history -m HEAD')
    result = run(args, root)
    if result.returncode not in (0, 10) or not report.is_file():
        raise ValueError('Secret scanner failed; raw diagnostics suppressed')
    raw = json.loads(report.read_text())
    if not isinstance(raw, list) or bool(raw) != (result.returncode == 10):
        raise ValueError('Inconsistent scanner report')
    rows = []
    for item in raw:
        file = item['File']
        if mode == 'dir' and Path(file).is_absolute():
            file = str(Path(file).relative_to(target))
        rows.append({'rule': item['RuleID'], 'file': file, 'line': item['StartLine'],
                     'commit': item['Commit'], 'fingerprint': item['Fingerprint']})
    return rows


def check(root, binary):
    shallow = run(['git', 'rev-parse', '--is-shallow-repository'], root)
    if shallow.returncode or shallow.stdout.strip() != b'false':
        raise ValueError('Full Git history is required; fetch with depth 0')
    files = run(['git', 'ls-files', '--cached', '-z'], root)
    if files.returncode:
        raise ValueError('Cannot enumerate tracked files')
    exceptions = json.loads((root / 'security/secret-exceptions.json').read_text())['exceptions']
    with tempfile.TemporaryDirectory(prefix='secret-scan-') as directory:
        tmp = Path(directory)
        (tmp / 'ignore').write_text('')
        snapshot = tmp / 'tracked'
        snapshot.mkdir()
        for name in files.stdout.decode().split('\0'):
            if not name:
                continue
            relative = Path(name)
            source = root / relative
            if relative.is_absolute() or '..' in relative.parts or any((root / Path(*relative.parts[:i])).is_symlink() for i in range(1, len(relative.parts) + 1)):
                raise ValueError('Unsupported tracked path or symlink')
            if not source.is_file():
                raise ValueError('Tracked file missing or unsupported')
            destination = snapshot / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        current = classify(scan(binary, root, snapshot, 'dir', tmp), exceptions, False)
        history = classify(scan(binary, root, root, 'git', tmp), exceptions, True)
    return {'complete': True, 'tracked_files': len([x for x in files.stdout.split(b'\0') if x]),
            'current': current, 'history': history,
            'passed': all(x['acknowledged'] for x in current + history)}


def main():
    output = ROOT / '.ci-artifacts/secrets/summary.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'complete': False, 'passed': False}) + '\n')
    try:
        result = check(ROOT, scanner(ROOT))
        output.write_text(json.dumps(result, indent=2) + '\n')
        pending = sum(not x['acknowledged'] for x in result['current'] + result['history'])
        print(f"Secret scan complete: {pending} blocking findings; {len(result['history'])} historical findings. See location-only summary.")
        return 0 if result['passed'] else 1
    except Exception:
        print('Secret scan incomplete; diagnostics suppressed to protect credential values.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
