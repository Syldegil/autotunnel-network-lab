#!/usr/bin/env python3
"""Fetch only the official Gradle wrapper, checking the published SHA-256."""
import hashlib,pathlib,urllib.request
p=pathlib.Path(__file__).resolve().parent/'gradle/wrapper/gradle-wrapper.jar'
expected='81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
data=p.read_bytes() if p.exists() else urllib.request.urlopen('https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar',timeout=60).read()
if hashlib.sha256(data).hexdigest()!=expected:raise SystemExit('Wrapper checksum mismatch')
if not p.exists():p.write_bytes(data)
print('Official Gradle 8.13 wrapper verified')
