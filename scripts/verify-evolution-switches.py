"""Check forward-compatible switches against the exact core candidate's evolution inventories.

The integration deliberately requires a top-level default in every production
switch. This stronger rule also covers future evolutionary enum imports without
maintaining a selector-type allowlist or relying on the currently known constants.
"""

import argparse
import json
from pathlib import Path
import re
import sys


REQUIRED_OWNERS = {
    "com.soklet.ServerType",
    "com.soklet.McpRequestOutcome",
    "com.soklet.StreamTerminationReason",
    "com.soklet.ShutdownComponentDisposition",
}
TOKENS = re.compile(
    r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|[^\s]',
    re.DOTALL,
)


def switches_without_default(source):
    tokens = [(match.group(), match.start()) for match in TOKENS.finditer(source)
              if not match.group().startswith(('//', '/*', '"', "'"))]
    missing = []
    switch_count = 0
    for index, (token, offset) in enumerate(tokens):
        if token != "switch":
            continue
        switch_count += 1
        cursor = index + 1
        if cursor >= len(tokens) or tokens[cursor][0] != "(":
            raise ValueError("Unrecognized switch syntax")
        parentheses = 1
        cursor += 1
        while cursor < len(tokens) and parentheses:
            current = tokens[cursor][0]
            parentheses += (current == "(") - (current == ")")
            cursor += 1
        if cursor >= len(tokens) or tokens[cursor][0] != "{":
            raise ValueError("Unrecognized switch body")
        depth = 1
        has_default = False
        cursor += 1
        while cursor < len(tokens) and depth:
            current = tokens[cursor][0]
            if current == "default" and depth == 1:
                has_default = True
            depth += (current == "{") - (current == "}")
            cursor += 1
        if depth:
            raise ValueError("Unclosed switch body")
        if not has_default:
            missing.append(source.count("\n", 0, offset) + 1)
    return switch_count, missing


def verify(core_directory, project_directory):
    owners = {}
    for relative in ("api/core-public-evolution-inventory.json", "api/mcp/mcp-public-evolution-inventory.json"):
        inventory = json.loads((Path(core_directory) / relative).read_text())
        for row in inventory["structuralOwners"]:
            owners[row["owner"]] = row
    for owner in sorted(REQUIRED_OWNERS):
        row = owners.get(owner)
        if row is None or row.get("kind") != "enum" or row.get("status") != "evolutionary":
            raise ValueError(f"Review changed or missing evolutionary enum contract: {owner}")

    count = 0
    imported = set()
    problems = []
    for path in sorted((Path(project_directory) / "src/main/java").rglob("*.java")):
        source = path.read_text()
        for owner in re.findall(r"\bimport\s+(com\.soklet\.[\w.]+)\s*;", source):
            if owners.get(owner, {}).get("status") == "evolutionary":
                imported.add(owner)
        switches, missing = switches_without_default(source)
        count += switches
        problems.extend(f"{path}:{line}: production switch needs a forward-compatible default" for line in missing)
    if not REQUIRED_OWNERS.issubset(imported):
        raise ValueError("Expected evolutionary enum imports were not checked")
    if problems:
        raise ValueError("\n".join(problems))
    return count, len(imported)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-directory", type=Path, required=True)
    parser.add_argument("--project-directory", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        switches, owners = verify(arguments.core_directory, arguments.project_directory)
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"Evolution switch verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {switches} production switches with defaults against {owners} imported evolutionary core types.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
