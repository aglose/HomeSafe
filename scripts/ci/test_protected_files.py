"""
Checks for the lines in "PR autopilot" and "Dependency updates" that refuse an agent's patch when it
touches workflows, agent settings or the Gradle wrapper. Those lines stay in the workflows rather
than moving to a script here: the jobs they are in check out the pull request's branch and hold a
write token, so a script would be the pull request's own copy. So each test lifts the lines out of
the workflow and runs them the way GitHub runs a step with no `shell:` set, `bash -e` with no
pipefail, in a scratch repository with a change staged.

    python3 -m unittest scripts/ci/test_protected_files.py

Standard library only; needs bash, git and grep on the path (as the hosted Ubuntu runner has).
"""

import os
import re
import subprocess
import tempfile
import textwrap
import unittest

WORKFLOWS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".github", "workflows")
CHECK = re.compile(r'^ *changed="\$RUNNER_TEMP/changed-files"\n.*?^ *if \[ -n "\$protected" \]; then\n.*?^ *fi\n', re.M | re.S)


def check_in(workflow):
    with open(os.path.join(WORKFLOWS, workflow), encoding="utf-8") as f:
        found = CHECK.findall(f.read())
    assert len(found) == 1, f"expected the check once in {workflow}, found {len(found)}"
    return textwrap.dedent(found[0])


class ProtectedFiles:
    workflow = None

    def run_check(self, staged, sha=None):
        """Runs the workflow's check with `staged(repo)` having changed the scratch repository.

        Returns (exit code, the message for a person or None, whether it went on to push)."""
        with tempfile.TemporaryDirectory() as temp:
            repo = os.path.join(temp, "repo")
            os.makedirs(repo)

            def git(*args):
                subprocess.run(
                    ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                     "-c", "core.excludesFile=", *args],
                    cwd=repo, check=True, capture_output=True,
                )

            git("init", "--quiet")
            for path in [".github/workflows/ci.yml", "shared/a.kt"]:
                self.write(repo, path)
            git("add", "--all")
            git("commit", "--quiet", "-m", "first")
            staged(repo)
            git("add", "--all")

            message = os.path.join(temp, "message")
            pushed = os.path.join(temp, "pushed")
            script = "\n".join([
                f'needs_a_human() {{ printf "%s" "$1" > "{message}"; exit 0; }}',
                check_in(self.workflow),
                f': > "{pushed}"',
            ])
            env = dict(os.environ, RUNNER_TEMP=temp, SHA=sha or "HEAD")
            result = subprocess.run(["bash", "-e", "-c", script], cwd=repo, env=env, capture_output=True)
            said = None
            if os.path.exists(message):
                with open(message, encoding="utf-8") as f:
                    said = f.read()
            return result.returncode, said, os.path.exists(pushed)

    @staticmethod
    def write(repo, path, text="x\n"):
        full = os.path.join(repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    def test_a_patch_elsewhere_goes_on_to_push(self):
        def staged(repo):
            self.write(repo, "shared/a.kt", "changed\n")
            # Near misses: these only look like the protected paths.
            self.write(repo, "shared/gradlew")
            self.write(repo, "gradle/libs.versions.toml")
            self.write(repo, "docs/.github/notes.md")
        self.assertEqual(self.run_check(staged), (0, None, True))

    def test_a_patch_touching_a_protected_path_is_refused_and_named(self):
        for path in [
            ".github/workflows/ci.yml",
            ".claude/settings.json",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradlew",
            "gradlew.bat",
        ]:
            with self.subTest(path):
                def staged(repo):
                    self.write(repo, "shared/a.kt", "changed\n")
                    self.write(repo, path, "changed\n")
                code, said, pushed = self.run_check(staged)
                self.assertEqual((code, pushed), (0, False))
                self.assertIn(path, said)
                self.assertNotIn("shared/a.kt", said)

    def test_a_path_git_would_quote_is_still_refused(self):
        # Without -z, git writes this one as ".github/caf\303\251 file.yml", quotes first.
        code, said, pushed = self.run_check(lambda repo: self.write(repo, ".github/café file.yml"))
        self.assertEqual((code, pushed), (0, False))
        self.assertIn(".github/café file.yml", said)

    def test_moving_a_file_out_of_a_protected_path_is_refused(self):
        def staged(repo):
            os.rename(os.path.join(repo, ".github/workflows/ci.yml"), os.path.join(repo, "shared/ci.yml"))
        code, said, pushed = self.run_check(staged)
        self.assertEqual((code, pushed), (0, False))
        self.assertIn(".github/workflows/ci.yml", said)

    def test_when_git_fails_nothing_is_pushed(self):
        code, said, pushed = self.run_check(lambda repo: self.write(repo, "shared/a.kt", "changed\n"), sha="not-a-commit")
        self.assertNotEqual(code, 0)
        self.assertFalse(pushed)
        self.assertIsNone(said)


class PrAutopilotTest(ProtectedFiles, unittest.TestCase):
    workflow = "pr-autopilot.yml"


class DependencyUpdatesTest(ProtectedFiles, unittest.TestCase):
    workflow = "dependency-updates.yml"


if __name__ == "__main__":
    unittest.main()
