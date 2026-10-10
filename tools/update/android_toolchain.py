"""Repo-local Android toolchain updater. Standard-library Python 3.10+ only."""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ANDROID_ROOT = ROOT / 'mobile' / 'android'
AGP_GRADLE = {'9.0': '9.1.0', '9.1': '9.3.1', '9.2': '9.4.1', '9.3': '9.5.0', '9.4': '9.6.0'}
API_AGP = {35: '8.6.0', 36: '8.9.1', 37: '9.1.1'}
SOURCES = {'agp': 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml',
           'kotlin': 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml',
           'ksp': 'https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml',
           'hilt': 'https://repo.maven.apache.org/maven2/com/google/dagger/hilt-android-gradle-plugin/maven-metadata.xml'}
REPOSITORY = 'https://dl.google.com/android/repository/repository2-3.xml'


def version(value):
    if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', value):
        raise ValueError(f'Expected stable numeric version: {value}')
    return tuple(int(part) for part in value.split('.')) + (0,) * (3 - len(value.split('.')))


def latest_patch(current, available):
    baseline = version(current)
    candidates = [current]
    for item in available:
        if re.fullmatch(r'\d+\.\d+(?:\.\d+)?', item) and version(item)[:2] == baseline[:2] and version(item) >= baseline:
            candidates.append(item)
    return max(candidates, key=version)


def validate_profile(profile):
    family = '.'.join(profile['agp'].split('.')[:2])
    if family not in AGP_GRADLE:
        raise ValueError(f'AGP {family} needs a reviewed compatibility-table update')
    if version(profile['gradle']) < version(AGP_GRADLE[family]):
        raise ValueError('Gradle is below the AGP compatibility minimum')
    if profile['compileSdk'] not in API_AGP or version(profile['agp']) < version(API_AGP[profile['compileSdk']]):
        raise ValueError('Android API/AGP combination is not reviewed')
    if not 26 <= profile['targetSdk'] <= profile['compileSdk']:
        raise ValueError('targetSdk must be between minSdk 26 and compileSdk')
    if profile['java'] != 21:
        raise ValueError('This project requires its verified JDK 21 toolchain')


@contextlib.contextmanager
def transaction(files, backup):
    backup.mkdir(parents=True, exist_ok=True)
    saved = {}
    for index, file in enumerate(files):
        target = backup / f'{index}-{file.name}'
        shutil.copy2(file, target)
        saved[file] = target
    try:
        yield
    except BaseException:
        for file, source in saved.items():
            shutil.copy2(source, file)
        raise


def update_sdk_text(text, compile_sdk, target_sdk):
    text = re.sub(r'(\bcompileSdk\s*=\s*)\d+', lambda m: m[1] + str(compile_sdk), text)
    if target_sdk is not None:
        text = re.sub(r'(\btargetSdk\s*=\s*)\d+', lambda m: m[1] + str(target_sdk), text)
    return text


def require_clean_lint(report):
    if not report.is_file():
        raise RuntimeError('Lint XML report is missing')
    errors = [issue for issue in ET.parse(report).getroot().findall('issue') if issue.get('severity') in ('Error', 'Fatal')]
    if errors:
        raise RuntimeError(f'Lint reports {len(errors)} errors; toolchain edits restored')


def fetch(url):
    if not url.startswith('https://'):
        raise ValueError('HTTPS is required')
    with urllib.request.urlopen(url, timeout=45) as response:
        if not response.url.startswith('https://'):
            raise ValueError('Insecure redirect rejected')
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError('Metadata size limit exceeded')
    return data


def sdk_packages(xml):
    document = ET.fromstring(xml)
    for element in document.iter():
        element.tag = element.tag.split('}')[-1]
    return [
        package for package in document.findall('remotePackage')
        if package.find('channelRef') is None or package.find('channelRef').get('ref') == 'channel-0']


def bootstrap_sdk(sdk, packages):
    manager = sdk / 'cmdline-tools/latest/bin/sdkmanager.bat'
    if manager.is_file():
        return manager
    package = next((p for p in packages if p.get('path') == 'cmdline-tools;latest'), None)
    if package is None:
        raise RuntimeError('Stable command-line tools were not found in Google SDK metadata')
    archive = next(a for a in package.findall('archives/archive') if a.findtext('host-os') == 'windows')
    complete = archive.find('complete')
    url = 'https://dl.google.com/android/repository/' + complete.findtext('url')
    size = int(complete.findtext('size'))
    if not url.startswith('https://dl.google.com/android/repository/') or size > 512 * 1024 * 1024:
        raise ValueError('Unexpected SDK archive')
    checksum = complete.find('checksum')
    algorithm = checksum.get('type', 'sha1').replace('-', '').lower()
    if algorithm not in ('sha1', 'sha256'):
        raise ValueError('Unsupported SDK checksum algorithm')
    archive_path = sdk.parent / 'android-command-line-tools.zip'
    digest = hashlib.new(algorithm)
    transferred = 0
    with urllib.request.urlopen(url, timeout=60) as response, archive_path.open('wb') as output:
        if not response.url.startswith('https://dl.google.com/'):
            raise ValueError('Unexpected SDK download redirect')
        while block := response.read(1024 * 1024):
            transferred += len(block)
            if transferred > size:
                raise ValueError('SDK download exceeds declared size')
            digest.update(block)
            output.write(block)
    if transferred != size or digest.hexdigest() != checksum.text.strip():
        raise ValueError('SDK archive checksum/size verification failed')
    destination = sdk / 'cmdline-tools/latest'
    with zipfile.ZipFile(archive_path) as archive_file:
        for entry in archive_file.infolist():
            name = pathlib.PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or not name.parts or name.parts[0] != 'cmdline-tools':
                raise ValueError('Unexpected SDK archive path')
            target = destination.joinpath(*name.parts[1:])
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive_file.open(entry) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
    return manager


def current_profile():
    catalog = (ANDROID_ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8-sig')
    app = (ANDROID_ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8-sig')
    wrapper = (ANDROID_ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    result = {'java': 21, 'gradle': re.search(r'gradle-([\d.]+)-bin', wrapper)[1],
              'compileSdk': int(re.search(r'compileSdk\s*=\s*(\d+)', app)[1]),
              'targetSdk': int(re.search(r'targetSdk\s*=\s*(\d+)', app)[1]),
              'ndk': re.search(r'ndkVersion\s*=\s*"([^"]+)"', app)[1]}
    for name, key in [('agp', 'androidGradlePlugin'), ('kotlin', 'kotlin'), ('ksp', 'ksp'), ('hilt', 'hilt')]:
        result[name] = re.search(r'^' + key + r'\s*=\s*"([^"]+)"', catalog, re.M)[1]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Apply candidates, build/test/lint, restore edits on failure')
    parser.add_argument('--profile', type=pathlib.Path, help='Explicit reviewed JSON profile for coordinated version/SDK upgrades')
    parser.add_argument('--target-sdk', type=int, help='Explicitly opt in to a target SDK behavior migration')
    args = parser.parse_args()
    run = ROOT / 'dist/updates/android' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    run.mkdir(parents=True)
    current = current_profile()
    candidate = dict(current)
    if args.profile:
        candidate.update(json.loads(args.profile.read_text(encoding='utf-8-sig')))
    report = {'current': current, 'candidate': candidate, 'sources': SOURCES, 'held': []}
    try:
        packages = sdk_packages(fetch(REPOSITORY))
        if not args.profile:
            for family, url in SOURCES.items():
                versions = [e.text for e in ET.fromstring(fetch(url)).findall('./versioning/versions/version')]
                candidate[family] = latest_patch(current[family], versions)
                stable = [v for v in versions if re.fullmatch(r'\d+\.\d+(?:\.\d+)?', v)]
                report['held'].append({'family': family, 'latestStable': max(stable, key=version), 'reason': 'Major/minor migrations require a reviewed profile'})
            platform_paths = [p.get('path') for p in packages if re.fullmatch(r'platforms;android-\d+', p.get('path', ''))]
            available_apis = [int(p.split('-')[-1]) for p in platform_paths]
            candidate['compileSdk'] = max([current['compileSdk']] + [api for api in available_apis if api in API_AGP and version(candidate['agp']) >= version(API_AGP[api])])
            report['held'].append({'family': 'Android SDK', 'latestStable': max(available_apis), 'reason': 'Unknown API compatibility is held; targetSdk stays unchanged by default'})
        if args.target_sdk is not None:
            candidate['targetSdk'] = args.target_sdk
        validate_profile(candidate)
        for family in ('agp', 'gradle', 'kotlin', 'ksp', 'hilt'):
            if version(candidate[family]) < version(current[family]):
                raise ValueError(f'Downgrade rejected: {family}')
        if candidate['compileSdk'] < current['compileSdk'] or candidate['targetSdk'] < current['targetSdk']:
            raise ValueError('SDK downgrade rejected')
        build_tools = [p.get('path').split(';')[1] for p in packages if re.fullmatch(r'build-tools;\d+\.\d+\.\d+', p.get('path', ''))]
        candidate['buildTools'] = max(build_tools, key=version)
        report['status'] = 'checked; no files changed'
        (run / 'plan.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2))
        if not args.apply:
            print(f'Check-only report: {run}')
            return
        configured_java = pathlib.Path(os.environ.get('JAVA_HOME', ''))
        java_home = ROOT / '.cache/jdk-21'
        if not (java_home / 'bin/java.exe').is_file() and not (configured_java / 'bin/java.exe').is_file():
            raise RuntimeError('Configure JAVA_HOME to JDK 21 before applying updates')
        if not (java_home / 'bin/java.exe').is_file():
            release = (configured_java / 'release').read_text()
            if not re.search(r'JAVA_VERSION="21(?:\.|\")', release):
                raise RuntimeError('Configured JDK must be version 21')
            shutil.copytree(configured_java, java_home)
        sdk = ROOT / '.cache/android-sdk'
        sdk.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
                   ANDROID_USER_HOME=str(ROOT / '.android'), GRADLE_USER_HOME=str(ROOT / '.gradle-local'))
        (ROOT / '.android').mkdir(exist_ok=True)
        gradle = str(ANDROID_ROOT / 'gradlew.bat')
        tracked = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard'], cwd=ROOT, text=True).splitlines()
        scripts = [ROOT / p for p in set(tracked) if p.endswith('build.gradle.kts') and (ROOT / p).is_file()]
        files = scripts + [ANDROID_ROOT / p for p in ('gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties',
                                                       'gradle/wrapper/gradle-wrapper.jar', 'gradlew', 'gradlew.bat')]
        with transaction(files, run / 'backup'):
            manager = bootstrap_sdk(sdk, packages)
            with (run / 'verification.log').open('w', encoding='utf-8') as log:
                subprocess.run([str(manager), f'--sdk_root={sdk}', '--channel=0', 'platform-tools',
                                f'platforms;android-{candidate["compileSdk"]}', f'build-tools;{candidate["buildTools"]}',
                                f'ndk;{candidate["ndk"]}'], cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                               stdout=log, stderr=subprocess.STDOUT, check=True)
                required_sdk_files = [sdk / f'platforms/android-{candidate["compileSdk"]}/android.jar',
                                      sdk / f'build-tools/{candidate["buildTools"]}/aapt2.exe',
                                      sdk / f'ndk/{candidate["ndk"]}/source.properties']
                if not all(file.is_file() for file in required_sdk_files):
                    raise RuntimeError('SDK installation did not complete; accept licenses interactively with the repo-local sdkmanager --licenses, then retry')
                for script in scripts:
                    script.write_text(update_sdk_text(script.read_text(encoding='utf-8-sig'), candidate['compileSdk'],
                                                      candidate['targetSdk'] if args.target_sdk or args.profile else None), encoding='utf-8')
                catalog = ANDROID_ROOT / 'gradle/libs.versions.toml'
                text = catalog.read_text(encoding='utf-8-sig')
                for family, key in [('agp', 'androidGradlePlugin'), ('kotlin', 'kotlin'), ('ksp', 'ksp'), ('hilt', 'hilt')]:
                    text = re.sub(r'(^' + key + r'\s*=\s*")[^"]+("\s*$)', lambda m: m[1] + candidate[family] + m[2], text, flags=re.M)
                catalog.write_text(text, encoding='utf-8')
                wrapper = ANDROID_ROOT / 'gradle/wrapper/gradle-wrapper.properties'
                text = re.sub(r'gradle-[\d.]+-bin', f'gradle-{candidate["gradle"]}-bin', wrapper.read_text())
                checksum = fetch(f'https://services.gradle.org/distributions/gradle-{candidate["gradle"]}-bin.zip.sha256').decode().strip()
                if not re.fullmatch('[a-fA-F0-9]{64}', checksum):
                    raise ValueError('Invalid Gradle distribution checksum')
                text = re.sub(r'^distributionSha256Sum=.*\n?', '', text, flags=re.M)
                wrapper.write_text(text.rstrip() + '\ndistributionSha256Sum=' + checksum + '\n')
                options = ['--no-daemon', '--no-configuration-cache', '--console=plain', '--max-workers=2',
                           '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.problems.report=false',
                           f'-Porg.gradle.java.installations.paths={java_home}', '-Porg.gradle.java.installations.auto-download=false']
                for _ in range(2):
                    subprocess.run([gradle, 'wrapper', '--gradle-version', candidate['gradle'], '--gradle-distribution-sha256-sum', checksum] + options,
                                   cwd=ANDROID_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                subprocess.run([gradle, ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease'] + options,
                               cwd=ANDROID_ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                require_clean_lint(ANDROID_ROOT / 'app/build/reports/lint-results-debug.xml')
        report['status'] = 'verified and applied; live device checks still required'
    except Exception as error:
        report['status'] = 'failed; source edits restored if apply started'
        report['error'] = str(error)
        raise
    finally:
        (run / 'result.json').write_text(json.dumps(report, indent=2))
        print(f'Report: {run}')


if __name__ == '__main__':
    main()

