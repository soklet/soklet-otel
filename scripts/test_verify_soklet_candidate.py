import importlib.util
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "verify_soklet_candidate", SCRIPT_DIRECTORY / "verify-soklet-candidate.py"
)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
COMMIT = "a" * 40
POM_OPEN = '<project xmlns="http://maven.apache.org/POM/4.0.0">'
CORE_POM = POM_OPEN + """
<groupId>com.soklet</groupId><artifactId>soklet</artifactId><version>4.0.0</version>
</project>
"""
OTEL_POM = POM_OPEN + """
<properties><soklet.version>4.0.0</soklet.version></properties>
<dependencies><dependency><groupId>com.soklet</groupId><artifactId>soklet</artifactId>
<version>${soklet.version}</version></dependency></dependencies>
</project>
"""


class CandidateVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.core = Path(self.temporary.name) / "core"
        self.otel = Path(self.temporary.name) / "otel"
        self.core.mkdir()
        self.otel.mkdir()
        self.write_poms()
        self.commit = patch.object(VERIFIER, "read_commit", return_value=COMMIT).start()
        self.addCleanup(patch.stopall)

    def write_poms(self, core=CORE_POM, otel=OTEL_POM):
        (self.core / "pom.xml").write_text(core, encoding="utf-8")
        (self.otel / "pom.xml").write_text(otel, encoding="utf-8")

    def verify(self, expected=COMMIT):
        return VERIFIER.verify_candidate(expected, self.core, self.otel)

    def test_matching_candidate(self):
        self.assertEqual((COMMIT, "4.0.0", "4.0.0"), self.verify())

    def test_rejects_mutable_or_abbreviated_selectors(self):
        for selector in ("master", "main", "v4.0.0", COMMIT[:7], COMMIT.upper(), ""):
            with self.subTest(selector=selector), self.assertRaisesRegex(ValueError, "exact"):
                self.verify(selector)
        self.commit.assert_not_called()

    def test_rejects_different_checkout(self):
        self.commit.return_value = "b" * 40
        with self.assertRaisesRegex(ValueError, "Core checkout"):
            self.verify()

    def test_rejects_wrong_core_coordinates(self):
        self.write_poms(core=CORE_POM.replace("<artifactId>soklet<", "<artifactId>other<"))
        with self.assertRaisesRegex(ValueError, "com.soklet:soklet"):
            self.verify()

    def test_rejects_different_core_version(self):
        self.write_poms(core=CORE_POM.replace("<version>4.0.0</version>", "<version>3.0.0</version>"))
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.verify()

    def test_rejects_unresolved_core_version(self):
        self.write_poms(core=CORE_POM.replace("<version>4.0.0</version>", "<version>${revision}</version>"))
        with self.assertRaisesRegex(ValueError, "literal"):
            self.verify()

    def test_rejects_missing_declared_baseline(self):
        self.write_poms(otel=OTEL_POM.replace("<soklet.version>4.0.0</soklet.version>", ""))
        with self.assertRaisesRegex(ValueError, "literal"):
            self.verify()

    def test_rejects_dependency_bypassing_declared_baseline(self):
        self.write_poms(otel=OTEL_POM.replace("${soklet.version}", "3.0.0"))
        with self.assertRaisesRegex(ValueError, "must use the declared"):
            self.verify()

    def test_workflow_uses_one_immutable_default_and_no_version_override(self):
        workflow = (SCRIPT_DIRECTORY.parent / ".github/workflows/ci.yml").read_text(
            encoding="utf-8"
        )
        default = re.search(r"(?m)^        default: ([0-9a-f]{40})$", workflow).group(1)
        self.assertIn("inputs.soklet_ref || '" + default + "'", workflow)
        self.assertIn("ref: ${{ env.SOKLET_CANDIDATE_SHA }}", workflow)
        self.assertNotIn("-Dsoklet.version", workflow)
        self.assertLess(workflow.index("verify-soklet-candidate.py"), workflow.index("-DskipTests"))


if __name__ == "__main__":
    unittest.main()
