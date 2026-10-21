#!/usr/bin/env python3
"""Validate, stage and publish the single current Hydros Android release format."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
PACKAGE = "org.cqutosp.hydros"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def version_code(version):
    require(VERSION.fullmatch(version), "Expected a stable version X.Y.Z without leading zeroes")
    major, minor, patch = map(int, version.split("."))
    require(minor < 1000 and patch < 1000, "Minor and patch must be below 1000")
    code = major * 1_000_000 + minor * 1_000 + patch
    require(1 <= code <= 2_100_000_000, "Version is outside the Android versionCode range")
    return code


def read_version(root=ROOT):
    values = re.findall(r"^hydrosVersion=(.+)$", (root / "gradle.properties").read_text(), re.MULTILINE)
    require(len(values) == 1, "Expected one hydrosVersion in gradle.properties")
    version_code(values[0])
    return values[0]


def output(values):
    for key, value in values.items():
        print(f"{key}={value}")
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")


def source(tag, root=ROOT):
    require(tag.startswith("v"), "Expected a release tag vX.Y.Z")
    version_code(tag[1:])
    version = read_version(root)
    require(tag == f"v{version}", "Tag and hydrosVersion differ")
    commit = run("git", "rev-parse", "HEAD", cwd=root)
    require(run("git", "rev-parse", f"refs/tags/{tag}^{{commit}}", cwd=root) == commit,
            "Checkout does not match the tagged commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/master"],
                   cwd=root, check=True)
    return dict(tag=tag, version=version, version_code=version_code(version), commit=commit,
                epoch=run("git", "show", "-s", "--format=%ct", commit, cwd=root))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tools_directory():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "Set ANDROID_HOME to inspect an APK")
    return Path(sdk) / "build-tools" / "36.0.0"


def inspect_apk(apk, version, certificate):
    code = version_code(version)
    require(re.fullmatch(r"[a-f0-9]{64}", certificate), "Expected a SHA-256 signing certificate")
    require(apk.is_file() and apk.stat().st_size > 0, "Missing or empty APK")
    tools = tools_directory()
    badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    require(package and package.groups() == (PACKAGE, str(code), version), "APK package/version mismatch")
    require("application-debuggable" not in badging, "Release APK must not be debuggable")
    signature = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signature)
    require(signers == [certificate], "APK signing certificate mismatch")
    require(re.search(r"Verified using v[23] scheme .*: true", signature), "APK must use v2 or v3 signing")
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
        require(abis == {"arm64-v8a"}, f"Expected only arm64-v8a native libraries, got {sorted(abis)}")
        require(archive.getinfo("assets/project_license.txt").file_size > 0, "Missing project license")
        require(any("aboutlibraries" in name for name in archive.namelist()), "Missing third-party licenses")


def apk_name(version):
    version_code(version)
    return f"Hydros-{version}.apk"


def stage(apk, directory, version, commit, certificate):
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "Expected a full Git commit SHA")
    inspect_apk(apk, version, certificate)
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / apk_name(version)
    shutil.copyfile(apk, destination)
    record = dict(schemaVersion=1, tag=f"v{version}", version=version, versionCode=version_code(version),
                  commit=commit, certificateSha256=certificate,
                  apk=dict(name=destination.name, sha256=digest(destination), size=destination.stat().st_size))
    (directory / "release.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    (directory / "SHA256SUMS").write_text(checksums(directory, version), encoding="utf-8")


def checksums(directory, version):
    return "".join(f"{digest(directory / name)}  {name}\n" for name in [apk_name(version), "release.json"])


def verify_directory(directory, version, commit, certificate):
    record = json.loads((directory / "release.json").read_text())
    expected = dict(schemaVersion=1, tag=f"v{version}", version=version, versionCode=version_code(version),
                    commit=commit, certificateSha256=certificate)
    require(all(record.get(key) == value for key, value in expected.items()), "Release provenance mismatch")
    apk = directory / apk_name(version)
    require(record.get("apk") == dict(name=apk.name, sha256=digest(apk), size=apk.stat().st_size),
            "APK digest/size mismatch")
    require((directory / "SHA256SUMS").read_text() == checksums(directory, version), "Checksum file mismatch")
    inspect_apk(apk, version, certificate)
    return record


class GitHub:
    def __init__(self, repository):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Invalid repository")
        self.repository = repository
        self.base = f"repos/{repository}"

    def api(self, path, method="GET", body=None):
        args = ["gh", "api", "--method", method, f"{self.base}/{path}"]
        if body is not None:
            args.extend(["--input", "-"])
        result = subprocess.check_output(args, input=json.dumps(body) if body is not None else None, text=True)
        return json.loads(result) if result.strip() else None

    def releases(self):
        pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"{self.base}/releases?per_page=100"))
        return [release for page in pages for release in page]

    def find(self, tag):
        return next((release for release in self.releases() if release["tag_name"] == tag), None)

    def resolve_tag(self, tag):
        # target_commitish is ignored by GitHub for existing tags; resolve the ref itself.
        ref = self.api(f"git/ref/tags/{tag}")["object"]
        while ref["type"] == "tag":
            ref = self.api(f"git/tags/{ref['sha']}")["object"]
        require(ref["type"] == "commit", "Release tag must point to a commit")
        return ref["sha"]

    def download(self, release, directory, version):
        directory.mkdir(parents=True, exist_ok=True)
        for name in [apk_name(version), "release.json", "SHA256SUMS"]:
            matches = [asset for asset in release["assets"] if asset["name"] == name]
            require(len(matches) == 1, f"Missing or ambiguous release asset: {name}")
            with (directory / name).open("wb") as stream:
                subprocess.run(["gh", "api", f"{self.base}/releases/assets/{matches[0]['id']}",
                                "-H", "Accept: application/octet-stream"], stdout=stream, check=True)

    def upload(self, tag, directory, version):
        subprocess.run(["gh", "release", "upload", tag, "--repo", self.repository, "--clobber",
                        *(str(directory / name) for name in [apk_name(version), "release.json", "SHA256SUMS"])], check=True)


def verify_remote(github, release, directory, version, commit, certificate):
    require(not release["prerelease"], "Cannot reuse a prerelease")
    require(github.resolve_tag(release["tag_name"]) == commit, "Existing release tag points to a different commit")
    require({asset["name"] for asset in release["assets"]} == {apk_name(version), "release.json", "SHA256SUMS"},
            "Release asset set mismatch")
    github.download(release, directory, version)
    return verify_directory(directory, version, commit, certificate)


def reuse(github, directory, version, commit, certificate):
    release = github.find(f"v{version}")
    if release is None:
        return False
    require(github.resolve_tag(f"v{version}") == commit, "Existing release tag points to a different commit")
    # Incomplete drafts can be rebuilt and replaced. Published assets are never replaced.
    if release["draft"]:
        return False
    verify_remote(github, release, directory, version, commit, certificate)
    return True


def should_be_latest(releases, version):
    current = tuple(map(int, version.split(".")))
    stable = [release["tag_name"][1:] for release in releases
              if not release["draft"] and not release["prerelease"]
              and release["tag_name"].startswith("v") and VERSION.fullmatch(release["tag_name"][1:])]
    return all(current >= tuple(map(int, other.split("."))) for other in stable)


def publish(github, directory, version, commit, certificate):
    local = verify_directory(directory, version, commit, certificate)
    tag = f"v{version}"
    require(github.resolve_tag(tag) == commit, "Release tag moved before publication")
    release = github.find(tag)
    if release and not release["draft"]:
        with tempfile.TemporaryDirectory() as temp:
            remote = verify_remote(github, release, Path(temp), version, commit, certificate)
        require(remote == local, "Published release differs; refusing to replace immutable assets")
        print(f"{tag} already published and verified")
        return
    if release:
        require(not release["prerelease"], "Cannot publish a prerelease draft as a stable release")
        require(all(asset["name"] in {apk_name(version), "release.json", "SHA256SUMS"} for asset in release["assets"]),
                "Draft contains unexpected assets")
    else:
        notes = github.api("releases/generate-notes", "POST", dict(tag_name=tag, target_commitish=commit))
        release = github.api("releases", "POST", dict(tag_name=tag, target_commitish=commit,
                             name=f"Hydros {version}", body=notes["body"], draft=True, prerelease=False))
    github.upload(tag, directory, version)
    with tempfile.TemporaryDirectory() as temp:
        remote = verify_remote(github, github.find(tag), Path(temp), version, commit, certificate)
    require(remote == local, "Uploaded release differs from verified build")
    # Catch moved tags before making any downloaded package visible to the updater.
    require(github.resolve_tag(tag) == commit, "Release tag moved during publication")
    latest = should_be_latest(github.releases(), version)
    github.api(f"releases/{release['id']}", "PATCH", dict(draft=False, make_latest=str(latest).lower()))
    print(f"Published {tag}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("version")
    sub.add_parser("source").add_argument("--tag", required=True)
    for command in ["stage", "reuse", "publish"]:
        item = sub.add_parser(command)
        item.add_argument("--directory", type=Path, default=ROOT / "release-apks")
        item.add_argument("--version", required=True)
        item.add_argument("--commit", required=True)
        item.add_argument("--certificate", required=True)
        if command == "stage":
            item.add_argument("--apk", type=Path, required=True)
        else:
            item.add_argument("--repository", required=True)
    args = parser.parse_args()
    if args.command == "version":
        version = read_version()
        output(dict(version=version, version_code=version_code(version)))
    elif args.command == "source":
        output(source(args.tag))
    elif args.command == "stage":
        stage(args.apk, args.directory, args.version, args.commit, args.certificate)
    elif args.command == "reuse":
        reused = reuse(GitHub(args.repository), args.directory, args.version, args.commit, args.certificate)
        output(dict(reused=str(reused).lower()))
    elif args.command == "publish":
        publish(GitHub(args.repository), args.directory, args.version, args.commit, args.certificate)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f"Release failed: {error}")
