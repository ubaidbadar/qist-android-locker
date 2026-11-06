#!/usr/bin/env python3
"""Print CUSTOMER_APP_RELEASE_JSON from an actual, signature-verified universal APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse


def release(apk: Path, url: str, apksigner: str, aapt2: str, wifi_only: bool) -> dict:
    parsed = urlparse(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError('Use an immutable HTTPS URL without credentials or a fragment')
    verified = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', str(apk)], check=True, capture_output=True, text=True)
    certificates = set(re.findall(r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest: ([a-fA-F0-9]{64})$', verified.stdout, re.MULTILINE))
    signer_count = re.search(r'^Number of signers: (\d+)$', verified.stdout, re.MULTILINE)
    if not signer_count or signer_count[1] != '1' or len(certificates) != 1:
        raise ValueError('Exactly one APK signer is required')
    badging = subprocess.run([aapt2, 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.MULTILINE)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    if not package or package[1] != 'pk.qist.customer' or not sdk:
        raise ValueError('Expected the Qist customer universal APK and a declared minimum SDK')
    if "split='" in badging.splitlines()[0]:
        raise ValueError('Use a universal APK, not a split APK')
    size = apk.stat().st_size
    if not 0 < size <= 200 * 1024 * 1024 or not 0 < int(package[2]) <= 2_100_000_000 or int(sdk[1]) < 24:
        raise ValueError('APK size, version code or minimum SDK is outside supported limits')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return dict(packageName=package[1], versionCode=int(package[2]), minSdk=int(sdk[1]), apkUrl=url,
                sizeBytes=size, sha256=digest, signingCertificateSha256=next(iter(certificates)).lower(), wifiOnly=wifi_only)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('url')
    parser.add_argument('--apksigner', default='apksigner')
    parser.add_argument('--aapt2', default='aapt2')
    parser.add_argument('--allow-metered', action='store_true', help='Opt into customer mobile-data downloads')
    args = parser.parse_args()
    print(json.dumps(release(args.apk, args.url, args.apksigner, args.aapt2, not args.allow_metered), separators=(',', ':')))
