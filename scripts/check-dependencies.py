#!/usr/bin/env python3
"""Check resolved Maven dependencies and both npm lockfiles against OSV."""
import datetime as dt
import hashlib
import io
import json
import re
import sys
import urllib.request
import urllib.parse
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.ci-artifacts/dependencies'


class CheckError(ValueError):
    pass


def require(value, message):
    if not value:
        raise CheckError(message)


def inventory(tree, locks):
    packages = {}
    counts = {}
    def add(ecosystem, name, version, context):
        require(isinstance(name, str) and name and isinstance(version, str) and version,
                'Dependency lacks an exact package name/version.')
        require(not version.startswith(('^', '~', 'file:', 'git:', 'workspace:')) and 'SNAPSHOT' not in version, 'Unsupported non-release dependency version.')
        key = (ecosystem, name, version)
        packages.setdefault(key, set()).add(context)
    def walk(node):
        require(isinstance(node.get('children', []), list), 'Malformed Maven tree.')
        for child in node.get('children', []):
            require(child.get('scope') in ('compile', 'runtime', 'provided', 'test', 'system'), 'Unknown Maven scope.')
            add('Maven', child['groupId'] + ':' + child['artifactId'], child['version'], 'maven:' + child['scope'])
            walk(child)
    walk(tree)
    counts['maven'] = len(packages)
    require(counts['maven'] > 0, 'Empty resolved Maven inventory.')
    for source, lock in locks.items():
        require(lock.get('lockfileVersion') == 3 and isinstance(lock.get('packages'), dict), 'Expected npm lockfile v3.')
        count = 0
        for location, package in lock['packages'].items():
            if not location:
                continue  # Application root is not an external dependency.
            require('node_modules/' in location and not package.get('link'), 'Unsupported linked npm package.')
            name = package.get('name') or location.rsplit('node_modules/', 1)[1]
            context = 'browser-test' if source == 'tests/browser' else ('frontend:development' if package.get('dev') else 'frontend:runtime-or-optional')
            add('npm', name, package.get('version'), context)
            count += 1
        require(count > 0, 'Empty npm inventory: ' + source)
        counts[source] = count
    require(set(locks) == {'frontend', 'tests/browser'}, 'Both npm lockfiles are required.')
    return [{'ecosystem': e, 'name': n, 'version': v, 'contexts': sorted(contexts)}
            for (e, n, v), contexts in sorted(packages.items())], counts


def packaged_inventory(tree, jar, packages):
    by_filename = {}
    def walk(node):
        for child in node.get('children', []):
            if child.get('type') == 'jar':
                suffix = '-' + child['classifier'] if child.get('classifier') else ''
                filename = child['artifactId'] + '-' + child['version'] + suffix + '.jar'
                by_filename[filename] = ('Maven', child['groupId'] + ':' + child['artifactId'], child['version'])
            walk(child)
    walk(tree)
    with zipfile.ZipFile(jar) as archive:
        names = [name.rsplit('/', 1)[1] for name in archive.namelist()
                 if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
        require(names, 'Packaged runtime inventory is empty.')
        for name in names:
            if name in by_filename:
                continue
            boot_versions = {p['version'] for p in packages if p['ecosystem'] == 'Maven'
                             and p['name'].startswith('org.springframework.boot:')}
            require(len(boot_versions) == 1, 'Ambiguous Boot version in packaged artifact.')
            version = next(iter(boot_versions))
            require(name == 'spring-boot-jarmode-tools-' + version + '.jar',
                    'Packaged JAR and resolved inventory disagree; rebuild before scanning.')
            with zipfile.ZipFile(io.BytesIO(archive.read('BOOT-INF/lib/' + name))) as tool:
                manifest = tool.read('META-INF/MANIFEST.MF').decode()
            fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
            require(fields.get('Automatic-Module-Name') == 'spring.boot.jarmode.tools'
                    and fields.get('Implementation-Version') == version
                    and fields.get('Implementation-Title') == 'Spring Boot Jarmode Tools',
                    'Packager-added library identity does not match Boot.')
            key = ('Maven', 'org.springframework.boot:spring-boot-jarmode-tools', version)
            by_filename[name] = key
            packages.append({'ecosystem': key[0], 'name': key[1], 'version': version,
                             'contexts': ['packager-added']})
    shipped = {by_filename[name] for name in names}
    require(not any(name.startswith(('spring-boot-devtools-', 'lombok-')) for name in names), 'Development/compiler libraries must not ship.')
    for package in packages:
        if (package['ecosystem'], package['name'], package['version']) in shipped:
            package['contexts'] = sorted(set(package['contexts']) | {'packaged-runtime'})
    return len(names)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def request(path, body=None):
    require(path == '/v1/querybatch' or re.fullmatch(r'/v1/vulns/[A-Za-z0-9._-]+', path), 'Invalid OSV path.')
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request('https://api.osv.dev' + path, data=data,
                                 headers={'Content-Type': 'application/json', 'User-Agent': 'scheduling-dependency-check'})
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
            payload = response.read(32 * 1024 * 1024 + 1)
            require(len(payload) <= 32 * 1024 * 1024, 'OSV response exceeds size limit.')
            result = json.loads(payload)
            require(isinstance(result, dict) and 'error' not in result, 'Invalid OSV response.')
            return result
    except CheckError:
        raise
    except Exception as error:
        raise CheckError('OSV request failed; advisory verification is incomplete.') from error


def query(packages, api=request):
    found = []
    records = {}
    for start in range(0, len(packages), 100):
        pending = [(package, None, set()) for package in packages[start:start + 100]]
        for page in range(20):
            if not pending:
                break
            queries = []
            for package, token, _ in pending:
                item = {'package': {'ecosystem': package['ecosystem'], 'name': package['name']}, 'version': package['version']}
                if token:
                    item['page_token'] = token
                queries.append(item)
            response = api('/v1/querybatch', {'queries': queries})
            results = response.get('results')
            require(isinstance(results, list) and len(results) == len(pending), 'Incomplete OSV batch response.')
            following = []
            for (package, _, seen), result in zip(pending, results):
                require(isinstance(result, dict) and set(result) <= {'vulns', 'next_page_token'}, 'OSV package query failed or has an unsupported response.')
                vulns = result.get('vulns', [])
                require(isinstance(vulns, list), 'Malformed vulnerability list.')
                for vuln in vulns:
                    id = vuln.get('id')
                    require(isinstance(id, str) and re.fullmatch(r'[A-Za-z0-9._-]+', id), 'Malformed advisory ID.')
                    if id not in records:
                        record = api('/v1/vulns/' + id)
                        require(record.get('id') == id and isinstance(record.get('affected'), list) and record['affected'], 'Incomplete advisory detail.')
                        require(any(a.get('package', {}).get('ecosystem') == package['ecosystem']
                                    and a.get('package', {}).get('name') == package['name'] for a in record['affected']),
                                'Advisory details do not match the queried package.')
                        records[id] = record
                    record = records[id]
                    require(any(a.get('package', {}).get('ecosystem') == package['ecosystem']
                                and a.get('package', {}).get('name') == package['name'] for a in record['affected']),
                            'Advisory details do not match the queried package.')
                    withdrawn = record.get('withdrawn')
                    if withdrawn is not None:
                        require(isinstance(withdrawn, str), 'Invalid advisory withdrawal timestamp.')
                        stamp = dt.datetime.fromisoformat(withdrawn.replace('Z', '+00:00'))
                        require(stamp.tzinfo is not None and stamp <= dt.datetime.now(dt.timezone.utc), 'Invalid advisory withdrawal timestamp.')
                    # A batch match is authoritative for the exact version; preserve withdrawn records as evidence.
                    found.append({**package, 'id': id, 'withdrawn': record.get('withdrawn'),
                                  'summary': record.get('summary', ''), 'aliases': record.get('aliases', [])})
                token = result.get('next_page_token')
                if token:
                    require(isinstance(token, str) and token not in seen, 'Repeated OSV pagination token.')
                    following.append((package, token, seen | {token}))
            pending = following
        require(not pending, 'OSV pagination limit reached; results are incomplete.')
    unique = {(f['ecosystem'], f['name'], f['version'], f['id']): f for f in found}
    return list(unique.values()), records


def evaluate(findings, policy, today):
    entries = policy.get('exceptions')
    require(isinstance(entries, list), 'Malformed dependency exception policy.')
    exceptions = {}
    for entry in entries:
        key = tuple(entry[field] for field in ('ecosystem', 'name', 'version', 'id'))
        require(all(isinstance(part, str) and part for part in key), 'Exception must identify an exact package/version/advisory.')
        require(key not in exceptions, 'Duplicate exception.')
        expiry = dt.date.fromisoformat(entry['expires'])
        require(today < expiry <= today + dt.timedelta(days=90), 'Exception is expired or exceeds 90 days.')
        require(isinstance(entry.get('reason'), str) and len(entry['reason'].strip()) >= 20
                and isinstance(entry.get('owner'), str) and entry['owner'].strip()
                and isinstance(entry.get('reference'), str) and urllib.parse.urlsplit(entry['reference']).scheme == 'https'
                and urllib.parse.urlsplit(entry['reference']).netloc, 'Exception lacks rationale, owner or review reference.')
        require(isinstance(entry.get('contexts'), list) and entry['contexts'], 'Exception must name reviewed contexts.')
        exceptions[key] = entry
    unaccepted = []
    used = set()
    for finding in findings:
        if finding.get('withdrawn'):
            continue
        key = tuple(finding[field] for field in ('ecosystem', 'name', 'version', 'id'))
        entry = exceptions.get(key)
        if entry and sorted(entry['contexts']) == sorted(finding['contexts']):
            used.add(key)
        else:
            unaccepted.append(finding)
    require(used == set(exceptions), 'Unused/stale exception must be removed or reviewed.')
    return unaccepted


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = {'complete': False, 'source': 'OSV', 'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        paths = {'maven': ROOT / 'target/dependency-security/maven-tree.json',
                 'frontend': ROOT / 'frontend/package-lock.json', 'tests/browser': ROOT / 'tests/browser/package-lock.json',
                 'policy': ROOT / 'security/dependency-exceptions.json',
                 'packagedJar': ROOT / 'target/schedulingApp-0.0.1-SNAPSHOT.jar'}
        raw = {name: path.read_bytes() for name, path in paths.items()}
        tree = json.loads(raw['maven'])
        packages, counts = inventory(tree, {name: json.loads(raw[name]) for name in ('frontend', 'tests/browser')})
        counts['packagedLibraries'] = packaged_inventory(tree, ROOT / 'target/schedulingApp-0.0.1-SNAPSHOT.jar', packages)
        (OUTPUT / 'inventory.json').write_text(json.dumps(packages, indent=2) + '\n')
        findings, records = query(packages)
        (OUTPUT / 'advisories.json').write_text(json.dumps(records, indent=2) + '\n')
        (OUTPUT / 'findings.json').write_text(json.dumps(findings, indent=2) + '\n')
        unaccepted = evaluate(findings, json.loads(raw['policy']), dt.datetime.now(dt.timezone.utc).date())
        summary.update(complete=True, packages=len(packages), inputCounts=counts,
                       inputSha256={name: hashlib.sha256(value).hexdigest() for name, value in raw.items()},
                       advisoryMatches=len(findings), unaccepted=len(unaccepted))
        print(json.dumps(summary))
        if unaccepted:
            for finding in unaccepted:
                print(f"Review required: {finding['name']} {finding['version']} {finding['id']}", file=sys.stderr)
            return 1
        return 0
    except Exception:
        summary['error'] = 'Dependency check is incomplete; inspect inputs/service availability. No clean result established.'
        print(summary['error'], file=sys.stderr)
        return 1
    finally:
        (OUTPUT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    sys.exit(main())
