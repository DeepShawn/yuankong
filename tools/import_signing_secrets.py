#!/usr/bin/env python3
"""Import a private signing backup into GitHub Actions Secrets with GitHub CLI."""
import argparse
import base64
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='DeepShawn/yuankong')
    parser.add_argument('--keystore', type=Path, default=Path(__file__).resolve().parent / 'lancmd-release.jks')
    parser.add_argument('--properties', type=Path, default=Path(__file__).resolve().parent / 'keystore.properties')
    args = parser.parse_args()
    if not shutil.which('gh'):
        parser.error('Install GitHub CLI and sign in with permission to manage repository Secrets')
    try:
        props = {}
        for line in args.properties.read_text(encoding='utf-8-sig').splitlines():
            if not line.strip() or line.lstrip().startswith(('#', '!')):
                continue
            key, value = line.split('=', 1)
            props[key.strip()] = value
        values = {
            'ANDROID_KEYSTORE_BASE64': base64.b64encode(args.keystore.read_bytes()),
            'ANDROID_KEYSTORE_PASSWORD': props['storePassword'].encode(),
            'ANDROID_KEY_ALIAS': props['keyAlias'].encode(),
            'ANDROID_KEY_PASSWORD': props['keyPassword'].encode(),
        }
        if any(not value for value in values.values()):
            raise ValueError('Empty signing configuration')
    except (OSError, KeyError, ValueError):
        parser.error('Cannot load the private JKS and its keystore.properties configuration')
    for name, value in values.items():
        result = subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo],
                                input=value, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            print('GitHub rejected ' + name + '. Check authentication and repository Secrets write permission.', file=sys.stderr)
            return 1
        print('Stored ' + name)
    values.clear()
    props.clear()
    result = subprocess.run(['gh', 'secret', 'list', '--repo', args.repo], check=False)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
