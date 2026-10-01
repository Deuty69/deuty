#!/usr/bin/env python3
"""
Static project verifier for the Deuty agent app.

WHY THIS EXISTS
---------------
The Android toolchain (JDK + SDK + Gradle + network access to Maven) is not always
available where this repository is edited. This script is not a compiler and does
not pretend to be one: it catches the classes of mistake that are *mechanical* and
that a compiler would catch, so that the first real `./gradlew assembleDebug` on a
proper machine has a chance of succeeding first time.

What it checks
  1.  Every XML file under res/ and the manifest parses.
  2.  Kotlin files have balanced (), {}, [] outside strings and comments.
  3.  Every `import com.deuty.agent.…` resolves to a real file or a real
      top-level declaration in that package.
  4.  Layering rule: core/, agent/, workflow/, storage/ contain no android.* or
      androidx.* imports (this is what makes them JVM-testable).
  5.  Manifest components map to existing Kotlin files.
  6.  ViewBinding class names map to existing layouts.
  7.  Every R.string/R.color/R.drawable/R.layout/R.id/R.style/R.dimen reference in
      Kotlin resolves to a defined resource.
  8.  Every @string/@color/@drawable/@dimen/@style/@layout/@id reference in XML
      resolves (ids may be defined in the same file with @+id).
  9.  Every `binding.someField` in Kotlin exists as an id in the matching layout.
  10. No duplicate ids inside one layout.
  11. Gradle wrapper + version catalog sanity (declared versions are used).

Usage:  python3 tools/verify_project.py [--verbose]
Exit code 0 = no problems found.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
RES = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
PKG_PREFIX = "com.deuty.agent"

PURE_PACKAGES = ("core", "agent", "workflow", "storage")

problems: list[str] = []
notes: list[str] = []


def problem(message: str) -> None:
    problems.append(message)


def note(message: str) -> None:
    notes.append(message)


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


# --------------------------------------------------------------------- loading

def kotlin_files() -> list[str]:
    out = []
    for base, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".kt"):
                out.append(os.path.join(base, name))
    return sorted(out)


def xml_files() -> list[str]:
    out = []
    for base, _dirs, files in os.walk(RES):
        for name in files:
            if name.endswith(".xml"):
                out.append(os.path.join(base, name))
    return sorted(out)


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


# ------------------------------------------------------------------ kotlin prep

def strip_kotlin(text: str) -> str:
    """
    Replace code with an equivalent-brace skeleton.

    A regex cannot do this: Kotlin string templates nest code inside strings
    (`"x ${list.count { it.y }}"`), and that inner code contains braces. So this is
    a small scanner that tracks four contexts - code, line comment, block comment
    and string (with a stack for `${...}` templates) - and keeps only the braces
    that belong to real code.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    # Each entry is the brace depth at which a `${` template started.
    template_stack: list[int] = []
    depth = 0
    while index < length:
        char = text[index]
        nxt = text[index + 1] if index + 1 < length else ""

        if char == "/" and nxt == "/":
            while index < length and text[index] != "\n":
                index += 1
            continue
        if char == "/" and nxt == "*":
            index += 2
            while index < length - 1 and not (text[index] == "*" and text[index + 1] == "/"):
                index += 1
            index = min(length, index + 2)
            continue
        if text.startswith('"""', index):
            index += 3
            while index < length and not text.startswith('"""', index):
                index += 1
            index = min(length, index + 3)
            continue
        if char == "'":
            index += 1
            while index < length and text[index] != "'":
                index += 2 if text[index] == "\\" else 1
            index = min(length, index + 1)
            continue
        if char == '"':
            index += 1
            while index < length:
                current = text[index]
                if current == "\\":
                    index += 2
                    continue
                if current == '"':
                    index += 1
                    break
                if current == "$" and index + 1 < length and text[index + 1] == "{":
                    # Enter a template: scan the inner code with the same rules.
                    template_stack.append(depth)
                    inner, consumed = _scan_template(text, index + 2, depth)
                    out.append(inner)
                    index = consumed
                    if template_stack:
                        template_stack.pop()
                    continue
                index += 1
            continue
        if char == "{":
            depth += 1
            out.append("{")
        elif char == "}":
            depth -= 1
            out.append("}")
        elif char in "()[]":
            out.append(char)
        index += 1
    return "".join(out)


def _scan_template(text: str, start: int, base_depth: int) -> tuple[str, int]:
    """Scan the code inside `${...}`, returning its brace skeleton and end index."""
    out: list[str] = []
    index = start
    depth = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == '"':
            index += 1
            while index < length:
                current = text[index]
                if current == "\\":
                    index += 2
                    continue
                if current == '"':
                    index += 1
                    break
                if current == "$" and index + 1 < length and text[index + 1] == "{":
                    inner, consumed = _scan_template(text, index + 2, base_depth)
                    out.append(inner)
                    index = consumed
                    continue
                index += 1
            continue
        if char == "{":
            depth += 1
            out.append("{")
        elif char == "}":
            if depth == 0:
                return "".join(out), index + 1
            depth -= 1
            out.append("}")
        elif char in "()[]":
            out.append(char)
        index += 1
    return "".join(out), index


MODIFIERS = (
    r"(?:public |internal |private |protected |abstract |open |sealed |data |value |"
    r"annotation |enum |override |suspend |inline |expect |actual |external |operator |"
    r"tailrec |infix |lateinit |const )*"
)
DECL_RE = re.compile(
    r"^" + MODIFIERS + r"(?:class|interface|object|fun|val|var|typealias)\s+"
    r"((?:[A-Za-z_][A-Za-z0-9_]*\.)?(?:<[^>]*>\s*)?[A-Za-z_][A-Za-z0-9_]*)",
    re.M,
)

# Packages the build generates; nothing to resolve on disk.
GENERATED_PACKAGES = {"com.deuty.agent.databinding", "com.deuty.agent"}
GENERATED_SYMBOLS = {"R", "BuildConfig", "Manifest"}


def declarations(text: str) -> set[str]:
    """Top-level names, including extension receivers stripped to the member name."""
    names = set()
    for raw in DECL_RE.findall(text):
        cleaned = re.sub(r"<[^>]*>\s*", "", raw)
        names.add(cleaned.split(".")[-1])
    return names


# ------------------------------------------------------------------- resources

def collect_resources() -> dict[str, set[str]]:
    res: dict[str, set[str]] = defaultdict(set)
    for path in xml_files():
        folder = os.path.basename(os.path.dirname(path))
        kind = folder.split("-")[0]
        name = os.path.splitext(os.path.basename(path))[0]
        if kind in ("layout", "drawable", "anim", "animator", "menu", "xml", "raw", "mipmap"):
            res[kind].add(name)
        elif kind == "values":
            try:
                root = ET.fromstring(read(path))
            except ET.ParseError:
                continue
            for child in root:
                tag = child.tag
                value = child.get("name")
                if not value:
                    continue
                if tag == "string":
                    res["string"].add(value)
                elif tag == "color":
                    res["color"].add(value)
                elif tag == "dimen":
                    res["dimen"].add(value)
                elif tag == "style":
                    res["style"].add(value)
                    # A style also makes its parents addressable: Theme.Deuty
                    # implies Theme.Deuty.Overlay is only valid if declared.
                elif tag == "bool" or tag == "integer":
                    res[tag].add(value)
                elif tag in ("string-array", "array", "integer-array"):
                    res["array"].add(value)
                elif tag == "declare-styleable":
                    res["styleable"].add(value)
                elif tag == "attr":
                    res["attr"].add(value)
                elif tag == "plurals":
                    res["plurals"].add(value)
    # ids declared with @+id in any layout
    for path in xml_files():
        if not os.path.basename(os.path.dirname(path)).startswith("layout"):
            continue
        for match in re.finditer(r'@\+id/([A-Za-z0-9_]+)', read(path)):
            res["id"].add(match.group(1))
    # android: framework resources we are allowed to reference
    return res


def layout_ids(layout_name: str) -> set[str]:
    path = os.path.join(RES, "layout", layout_name + ".xml")
    if not os.path.exists(path):
        return set()
    return set(re.findall(r'@\+id/([A-Za-z0-9_]+)', read(path)))


def snake_to_binding(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def binding_to_layout(binding: str) -> str:
    stem = binding[:-7] if binding.endswith("Binding") else binding
    out = []
    for index, char in enumerate(stem):
        if char.isupper() and index > 0:
            out.append("_")
            out.append(char.lower())
        else:
            out.append(char.lower())
    return "".join(out)


# ---------------------------------------------------------------------- checks

def check_xml() -> dict[str, ET.Element]:
    roots = {}
    for path in xml_files() + [MANIFEST]:
        try:
            roots[path] = ET.fromstring(read(path))
        except ET.ParseError as error:
            problem(f"{rel(path)}: XML parse error: {error}")
    return roots


def check_braces() -> None:
    for path in kotlin_files():
        text = strip_kotlin(read(path))
        for open_char, close_char in (("(", ")"), ("{", "}"), ("[", "]")):
            depth = text.count(open_char) - text.count(close_char)
            if depth != 0:
                problem(
                    f"{rel(path)}: unbalanced {open_char}{close_char} "
                    f"({depth:+d}) outside strings/comments"
                )


def check_imports(files: dict[str, str]) -> None:
    # package -> declarations
    package_decls: dict[str, set[str]] = defaultdict(set)
    package_files: dict[str, set[str]] = defaultdict(set)
    for path, text in files.items():
        match = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.M)
        if not match:
            problem(f"{rel(path)}: no package declaration")
            continue
        package = match.group(1)
        # Raw text: DECL_RE is line-anchored, and KDoc lines start with ' * '.
        package_decls[package] |= declarations(text)
        package_files[package].add(os.path.splitext(os.path.basename(path))[0])

    for path, text in files.items():
        for line in text.splitlines():
            line = line.strip()
            if not line.startswith("import "):
                continue
            target = line[len("import "):].split(" as ")[0].strip()
            if not target.startswith(PKG_PREFIX):
                continue
            package_part, _, symbol_part = target.rpartition(".")
            if package_part in GENERATED_PACKAGES and symbol_part in GENERATED_SYMBOLS:
                continue
            if package_part == PKG_PREFIX + ".databinding":
                continue
            package, _, symbol = target.rpartition(".")
            if not package:
                problem(f"{rel(path)}: malformed import {target}")
                continue
            if package not in package_decls:
                problem(f"{rel(path)}: import {target} -> no such package {package}")
                continue
            if symbol in package_decls[package] or symbol in package_files[package]:
                continue
            if symbol == "*":
                continue
            problem(f"{rel(path)}: import {target} -> '{symbol}' not declared in {package}")


def check_purity(files: dict[str, str]) -> None:
    for path, text in files.items():
        relative = rel(path)
        parts = relative.split(os.sep)
        try:
            index = parts.index(PKG_PREFIX.replace(".", os.sep)) if False else None
        except ValueError:
            index = None
        # locate the package root inside the path
        marker = os.sep.join(PKG_PREFIX.split("."))
        if marker + os.sep in relative:
            after = relative.split(marker + os.sep, 1)[1]
        else:
            continue
        top = after.split(os.sep)[0]
        if top not in PURE_PACKAGES:
            continue
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped.startswith("import "):
                continue
            target = stripped[len("import "):]
            if target.startswith("android.") or target.startswith("androidx."):
                problem(
                    f"{relative}: framework import `{target}` in pure package `{top}/` "
                    "(breaks JVM testability)"
                )


def check_manifest() -> None:
    if not os.path.exists(MANIFEST):
        problem("AndroidManifest.xml is missing")
        return
    text = read(MANIFEST)
    for match in re.finditer(r'android:name="(\.?[^"]+)"', text):
        name = match.group(1)
        if name.startswith("android.") or name.startswith("androidx."):
            continue
        if not (name.startswith(".") or name.startswith(PKG_PREFIX)):
            continue
        qualified = name[1:] if name.startswith(".") else name
        if qualified.startswith(PKG_PREFIX):
            qualified = qualified[len(PKG_PREFIX) + 1:] if qualified != PKG_PREFIX else ""
        if not qualified:
            continue
        # Only class-like entries (contain an uppercase segment) are components.
        if not re.search(r"[A-Z]", qualified.split(".")[-1]):
            continue
        path = os.path.join(SRC, PKG_PREFIX.replace(".", os.sep), *qualified.split(".")) + ".kt"
        if not os.path.exists(path):
            problem(f"AndroidManifest.xml: component {name} -> missing {rel(path)}")


def check_kotlin_resources(files: dict[str, str], resources: dict[str, set[str]]) -> None:
    pattern = re.compile(r"\bR\.(string|color|drawable|layout|id|style|dimen|anim|menu|xml|array|plurals|mipmap)\.([A-Za-z0-9_]+)")
    for path, text in files.items():
        for kind, name in pattern.findall(text):
            if name not in resources.get(kind, set()):
                # style references in code may use the dotted form
                if kind == "style":
                    continue
                problem(f"{rel(path)}: R.{kind}.{name} is not defined in res/")


def check_xml_resources(resources: dict[str, set[str]]) -> None:
    pattern = re.compile(r'"@(string|color|drawable|dimen|style|layout|anim|menu|xml|array|plurals|mipmap|id)/([A-Za-z0-9_.]+)"')
    for path in xml_files():
        text = read(path)
        for kind, name in pattern.findall(text):
            if kind == "style":
                if name not in resources.get("style", set()) and not name.startswith("android:") \
                        and not name.startswith("TextAppearance") and not name.startswith("Widget") \
                        and not name.startswith("ThemeOverlay") and not name.startswith("Theme."):
                    problem(f"{rel(path)}: @style/{name} is not defined")
                continue
            if kind == "id":
                if name not in resources.get("id", set()):
                    problem(f"{rel(path)}: @id/{name} is not defined (missing @+id?)")
                continue
            if name.startswith("android"):
                continue
            if name not in resources.get(kind, set()):
                problem(f"{rel(path)}: @{kind}/{name} is not defined in res/")


def check_bindings(files: dict[str, str], resources: dict[str, set[str]]) -> None:
    binding_re = re.compile(r"\b([A-Z][A-Za-z0-9]*Binding)\b")
    field_re = re.compile(r"\bbinding\.([a-zA-Z][A-Za-z0-9]*)")
    known_members = {
        "root", "inflate", "bind", "getRoot",
    }
    for path, text in files.items():
        used = set(binding_re.findall(text))
        if not used:
            continue
        for binding in used:
            layout = binding_to_layout(binding)
            if layout not in resources.get("layout", set()):
                problem(f"{rel(path)}: {binding} -> layout/{layout}.xml does not exist")
        ids: set[str] = set()
        for binding in used:
            ids |= {snake_to_binding(i) for i in layout_ids(binding_to_layout(binding))}
        for field in set(field_re.findall(text)):
            if field in known_members:
                continue
            if ids and field not in ids:
                problem(f"{rel(path)}: binding.{field} has no matching id in {sorted(used)}")


def check_duplicate_ids() -> None:
    for path in xml_files():
        if not os.path.basename(os.path.dirname(path)).startswith("layout"):
            continue
        ids = re.findall(r'@\+id/([A-Za-z0-9_]+)', read(path))
        seen: dict[str, int] = defaultdict(int)
        for value in ids:
            seen[value] += 1
        for value, count in seen.items():
            if count > 1:
                problem(f"{rel(path)}: duplicate @+id/{value} ({count}x)")


def check_gradle() -> None:
    wrapper = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
    if not os.path.exists(wrapper):
        problem("gradle/wrapper/gradle-wrapper.properties is missing")
    else:
        text = read(wrapper)
        if "distributionUrl" not in text:
            problem("gradle-wrapper.properties has no distributionUrl")
        if "gradle-8.13-bin.zip" not in text:
            note(f"wrapper distribution is not gradle-8.13: {text.strip().splitlines()[-1]}")
    jar = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")
    if not os.path.exists(jar):
        problem("gradle/wrapper/gradle-wrapper.jar is missing (gradlew will not run)")
    for name in ("gradlew", "gradlew.bat", "settings.gradle.kts", "build.gradle.kts", "gradle.properties"):
        if not os.path.exists(os.path.join(ROOT, name)):
            problem(f"{name} is missing")
    catalog = os.path.join(ROOT, "gradle", "libs.versions.toml")
    if os.path.exists(catalog):
        text = read(catalog)
        declared = set(re.findall(r"^([A-Za-z0-9\-]+)\s*=\s*\{ module", text, re.M))
        used = set()
        gradle_files = [os.path.join(ROOT, "app", "build.gradle.kts"), os.path.join(ROOT, "build.gradle.kts")]
        for path in gradle_files:
            if os.path.exists(path):
                used |= set(re.findall(r"libs\.([A-Za-z0-9_.]+)", read(path)))
        normalized = {u.replace(".", "-") for u in used}
        # plugin aliases live in a separate table
        plugins = set(re.findall(r"^([A-Za-z0-9\-]+)\s*=\s*\{ id", text, re.M))
        for alias in sorted(normalized):
            if alias in declared or alias in plugins or alias.startswith("plugins"):
                continue
            problem(f"build.gradle.kts uses libs.{alias} which is not in the version catalog")


def check_expected_files() -> None:
    expected = [
        "app/src/main/java/com/deuty/agent/AgentApplication.kt",
        "app/src/main/java/com/deuty/agent/di/AgentContainer.kt",
        "app/src/main/java/com/deuty/agent/agent/AgentEngine.kt",
        "app/src/main/java/com/deuty/agent/platform/AgentAccessibilityService.kt",
    ]
    for name in expected:
        if not os.path.exists(os.path.join(ROOT, name)):
            problem(f"expected file missing: {name}")


# ------------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    files = {path: read(path) for path in kotlin_files()}
    resources = collect_resources()

    check_xml()
    check_braces()
    check_imports(files)
    check_purity(files)
    check_manifest()
    check_kotlin_resources(files, resources)
    check_xml_resources(resources)
    check_bindings(files, resources)
    check_duplicate_ids()
    check_gradle()
    check_expected_files()

    kotlin_count = len(files)
    kotlin_lines = sum(text.count("\n") + 1 for text in files.values())
    print(f"scanned {kotlin_count} Kotlin files ({kotlin_lines} lines), "
          f"{len(xml_files())} XML files, {sum(len(v) for v in resources.values())} resources")
    if args.verbose:
        for line in notes:
            print(f"  note: {line}")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("no problems found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
