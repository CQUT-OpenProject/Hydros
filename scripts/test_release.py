"""Release failures and retries must never expose a partial or replaced APK."""

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release

COMMIT = "a" * 40
CERTIFICATE = "b" * 64
VERSION = "0.1.0"


class FakeGitHub(release.GitHub):
    def __init__(self):
        self.entries = []
        self.files = {}
        self.writes = []
        self.tag_commit = COMMIT

    def releases(self):
        return self.entries

    def api(self, path, method="GET", body=None):
        if method != "GET":
            self.writes.append((path, method, body))
        if path == "releases/generate-notes":
            return {"body": "Automatically generated release notes"}
        if path == "releases" and method == "POST":
            entry = dict(body, id=1, assets=[])
            self.entries.append(entry)
            return entry
        if path == "releases/1" and method == "PATCH":
            self.entries[0].update(body)
            return self.entries[0]
        if path.startswith("git/ref/tags/"):
            return {"object": {"type": "commit", "sha": self.tag_commit}}
        raise AssertionError((path, method, body))

    def upload(self, tag, directory, version):
        self.writes.append(("upload", tag))
        entry = self.find(tag)
        self.files = {path.name: path.read_bytes() for path in directory.iterdir()}
        entry["assets"] = [{"name": name, "id": index} for index, name in enumerate(self.files)]

    def download(self, entry, directory, version):
        directory.mkdir(parents=True, exist_ok=True)
        for name in [release.apk_name(version), "release.json", "SHA256SUMS"]:
            (directory / name).write_bytes(self.files[name])


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "staged"
        self.apk = self.root / "app-release.apk"
        self.apk.write_bytes(b"signed arm64 APK fixture")
        self.inspector = patch.object(release, "inspect_apk")
        self.mock_inspector = self.inspector.start()
        self.addCleanup(self.inspector.stop)
        self.github = FakeGitHub()

    def stage(self):
        release.stage(self.apk, self.directory, VERSION, COMMIT, CERTIFICATE)

    def publish(self):
        release.publish(self.github, self.directory, VERSION, COMMIT, CERTIFICATE)

    def test_version_encoding_and_boundaries(self):
        self.assertEqual(1000, release.version_code("0.1.0"))
        self.assertEqual(1002010, release.version_code("1.2.10"))
        self.assertEqual(2100000000, release.version_code("2100.0.0"))
        for value in ["0.0.0", "01.0.0", "1.02.0", "v1.0.0", "1.0.0-beta", "1.1000.0", "1.0.1000", "2100.0.1"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_code(value)

    def test_canonical_version_rejects_missing_or_duplicate_value(self):
        path = self.root / "gradle.properties"
        for value in ["", "hydrosVersion=1.0.0\nhydrosVersion=2.0.0\n"]:
            path.write_text(value)
            with self.assertRaises(ValueError):
                release.read_version(self.root)
        path.write_text("hydrosVersion=1.0.2\n")
        self.assertEqual("1.0.2", release.read_version(self.root))

    def test_staging_names_and_checksums(self):
        self.stage()
        self.assertEqual({"Hydros-0.1.0.apk", "release.json", "SHA256SUMS"}, {p.name for p in self.directory.iterdir()})
        record = release.verify_directory(self.directory, VERSION, COMMIT, CERTIFICATE)
        self.assertEqual(1, record["schemaVersion"])
        self.assertEqual(COMMIT, record["commit"])

    def test_missing_apk_cannot_be_published(self):
        self.stage()
        (self.directory / release.apk_name(VERSION)).unlink()
        with self.assertRaises(FileNotFoundError):
            self.publish()
        self.assertEqual([], self.github.writes)

    def test_tampered_apk_or_checksums_cannot_be_published(self):
        for name in [release.apk_name(VERSION), "SHA256SUMS"]:
            self.stage()
            (self.directory / name).write_bytes(b"tampered")
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.publish()
        self.assertEqual([], self.github.writes)

    def test_wrong_source_or_certificate_cannot_be_published(self):
        self.stage()
        for commit, certificate in [("c" * 40, CERTIFICATE), (COMMIT, "d" * 64)]:
            with self.assertRaises(ValueError):
                release.publish(self.github, self.directory, VERSION, commit, certificate)
        self.assertEqual([], self.github.writes)

    def test_publishes_only_after_uploaded_assets_verify(self):
        self.stage()
        self.publish()
        self.assertFalse(self.github.entries[0]["draft"])
        self.assertEqual("true", self.github.entries[0]["make_latest"])
        self.assertEqual("Automatically generated release notes", self.github.entries[0]["body"])
        self.assertEqual("upload", self.github.writes[-2][0])
        self.assertEqual("PATCH", self.github.writes[-1][1])

    def test_corrupt_upload_remains_a_draft(self):
        self.stage()
        upload = self.github.upload

        def corrupt(*args):
            upload(*args)
            self.github.files[release.apk_name(VERSION)] = b"broken upload"

        with patch.object(self.github, "upload", side_effect=corrupt), self.assertRaises(ValueError):
            self.publish()
        self.assertTrue(self.github.entries[0]["draft"])

    def test_partial_draft_recovers_and_preserves_notes(self):
        self.stage()
        self.github.entries.append(dict(id=1, tag_name=f"v{VERSION}", target_commitish=COMMIT,
                                        prerelease=False, draft=True, body="Existing notes", assets=[]))
        self.assertFalse(release.reuse(self.github, self.root / "reuse", VERSION, COMMIT, CERTIFICATE))
        self.publish()
        self.assertFalse(self.github.entries[0]["draft"])
        self.assertEqual("Existing notes", self.github.entries[0]["body"])
        self.assertEqual("upload", self.github.writes[0][0])

    def test_published_retry_reuses_and_never_uploads(self):
        self.stage()
        self.publish()
        self.github.writes.clear()
        self.assertTrue(release.reuse(self.github, self.root / "reuse", VERSION, COMMIT, CERTIFICATE))
        self.publish()
        self.assertEqual([], self.github.writes)

    def test_symbolic_target_metadata_is_not_tag_provenance(self):
        self.stage()
        self.publish()
        self.github.entries[0]["target_commitish"] = "master"
        self.github.writes.clear()
        self.assertTrue(release.reuse(self.github, self.root / "reuse", VERSION, COMMIT, CERTIFICATE))
        self.publish()
        self.assertEqual([], self.github.writes)

    def test_published_retry_rejects_rebuilt_different_bytes(self):
        self.stage()
        self.publish()
        self.github.writes.clear()
        self.apk.write_bytes(b"different build")
        self.stage()
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.github.writes)

    def test_missing_or_extra_published_assets_block_reuse(self):
        self.stage()
        self.publish()
        assets = self.github.entries[0]["assets"]
        for invalid in [assets[:-1], assets + [{"name": "extra.apk", "id": 99}]]:
            self.github.entries[0]["assets"] = invalid
            with self.assertRaises(ValueError):
                release.reuse(self.github, self.root / "reuse", VERSION, COMMIT, CERTIFICATE)

    def test_moved_tag_blocks_publication(self):
        self.stage()
        self.github.tag_commit = "c" * 40
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual([], self.github.writes)

    def test_tag_moved_during_upload_remains_a_draft(self):
        self.stage()
        upload = self.github.upload

        def move_tag(*args):
            upload(*args)
            self.github.tag_commit = "c" * 40

        with patch.object(self.github, "upload", side_effect=move_tag), self.assertRaises(ValueError):
            self.publish()
        self.assertTrue(self.github.entries[0]["draft"])

    def test_old_release_never_becomes_latest(self):
        entries = [dict(tag_name="v1.2.10", draft=False, prerelease=False),
                   dict(tag_name="v99.0.0", draft=True, prerelease=False),
                   dict(tag_name="v99.0.1", draft=False, prerelease=True)]
        self.assertFalse(release.should_be_latest(entries, "1.2.9"))
        self.assertTrue(release.should_be_latest(entries, "1.2.11"))


class ApkInspectionTest(unittest.TestCase):
    def test_rejects_wrong_abi_signature_version_and_missing_license(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            badging = "package: name='dev.uednd.hydros' versionCode='1000' versionName='0.1.0'"
            signature = f"Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: {CERTIFICATE}"
            cases = [({"arm64-v8a"}, CERTIFICATE, badging, True, True),
                     ({"arm64-v8a", "x86_64"}, CERTIFICATE, badging, True, False),
                     ({"x86_64"}, CERTIFICATE, badging, True, False),
                     ({"arm64-v8a"}, "c" * 64, badging, True, False),
                     ({"arm64-v8a"}, CERTIFICATE, badging.replace("1000", "1"), True, False),
                     ({"arm64-v8a"}, CERTIFICATE, badging + "\napplication-debuggable", True, False),
                     ({"arm64-v8a"}, CERTIFICATE, badging, False, False)]
            for abis, certificate, package, license_present, valid in cases:
                with zipfile.ZipFile(apk, "w") as archive:
                    for abi in abis:
                        archive.writestr(f"lib/{abi}/library.so", b"native code")
                    archive.writestr("assets/project_license.txt", b"license" if license_present else b"")
                    archive.writestr("res/raw/aboutlibraries.json", b"{}")
                with self.subTest(abis=abis, package=package, certificate=certificate, license=license_present), \
                        patch.object(release, "tools_directory", return_value=Path("/sdk")), \
                        patch.object(release, "run", side_effect=[package, signature]):
                    if valid:
                        release.inspect_apk(apk, VERSION, certificate)
                    else:
                        with self.assertRaises(ValueError):
                            release.inspect_apk(apk, VERSION, certificate)


class SourceTest(unittest.TestCase):
    def test_tag_version_and_master_ancestry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(["git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()

            git("init", "-q")
            (root / "gradle.properties").write_text("hydrosVersion=0.1.0\n")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture")
            git("tag", "v0.1.0")
            git("update-ref", "refs/remotes/origin/master", "HEAD")
            self.assertEqual(git("rev-parse", "HEAD"), release.source("v0.1.0", root)["commit"])
            for tag in ["1.0.0", "v01.0.0", "v0.2.0", "v0.1.0-beta"]:
                with self.subTest(tag=tag), self.assertRaises(ValueError):
                    release.source(tag, root)
            (root / "another-file").write_text("not merged")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "unmerged")
            git("tag", "-f", "v0.1.0")
            with self.assertRaises(subprocess.CalledProcessError):
                release.source("v0.1.0", root)


if __name__ == "__main__":
    unittest.main()
