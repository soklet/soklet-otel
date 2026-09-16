"""Fail closed unless CI checks the exact core candidate against the declared baseline."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


NAMESPACES = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_text(project, path):
    value = project.findtext(path, namespaces=NAMESPACES)
    if value is None or not value.strip() or "${" in value:
        raise ValueError(f"POM must declare a literal value for {path}")
    return value.strip()


def read_commit(directory):
    return subprocess.check_output(
        ["git", "-C", str(directory), "rev-parse", "HEAD"], text=True
    ).strip()


def verify_candidate(expected_commit, core_directory, otel_directory):
    if re.fullmatch(r"[0-9a-f]{40}", expected_commit) is None:
        raise ValueError("Expected an exact 40-character lowercase core commit SHA")

    actual_commit = read_commit(core_directory)
    if actual_commit != expected_commit:
        raise ValueError(f"Core checkout is {actual_commit}, expected {expected_commit}")

    core = ET.parse(Path(core_directory) / "pom.xml").getroot()
    otel = ET.parse(Path(otel_directory) / "pom.xml").getroot()
    if (pom_text(core, "m:groupId"), pom_text(core, "m:artifactId")) != (
        "com.soklet", "soklet"
    ):
        raise ValueError("Core checkout must build com.soklet:soklet")

    core_version = pom_text(core, "m:version")
    declared_version = pom_text(otel, "m:properties/m:soklet.version")
    if core_version != declared_version:
        raise ValueError(
            f"Core candidate version {core_version} does not match the declared "
            f"soklet-otel baseline {declared_version}; do not override soklet.version"
        )

    core_dependencies = [
        dependency
        for dependency in otel.findall("m:dependencies/m:dependency", NAMESPACES)
        if dependency.findtext("m:groupId", namespaces=NAMESPACES) == "com.soklet"
        and dependency.findtext("m:artifactId", namespaces=NAMESPACES) == "soklet"
    ]
    if len(core_dependencies) != 1 or core_dependencies[0].findtext(
        "m:version", namespaces=NAMESPACES
    ) != "${soklet.version}":
        raise ValueError("The Soklet dependency must use the declared soklet.version property")

    return actual_commit, core_version, declared_version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--core-directory", type=Path, required=True)
    parser.add_argument("--otel-directory", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        commit, core_version, declared_version = verify_candidate(
            arguments.expected_commit, arguments.core_directory, arguments.otel_directory
        )
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as exception:
        print(f"Candidate verification failed: {exception}", file=sys.stderr)
        return 1
    print(f"Verified core candidate commit: {commit}")
    print(f"Core candidate coordinates: com.soklet:soklet:{core_version}")
    print(f"Declared soklet-otel baseline: com.soklet:soklet:{declared_version}")
    print("Tests and Javadoc must use the declared baseline without a version override.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
