#!/usr/bin/env python3
"""Fat-service / thin-controller architecture guard + clean-code checks.

Enforces the project's agreed architecture on every branch:

  Backend (ASP.NET Core)
    - THIN controllers: only HTTP mapping, auth attributes and delegation.
      No data access, hashing, or token creation inside Controllers/.
    - FAT services: all business rules live in Services/.
    - Controllers stay small and every action delegates to an injected service.

  Frontend (React + TS)
    - FAT api-service layer: raw HTTP (fetch/axios) lives only in
      src/api.ts (or src/services/). Components (.tsx) consume it.
    - No console.* / debugger leftovers in shipped code.

Clean-code hard rules (fail the build):
    - No Console.Write* outside host-bootstrap files (use ILogger).
    - No console.* / debugger in web/src.

Smells reported as warnings (do not fail, keep the gate green while
surfacing tech debt): TODO / FIXME / HACK markers.

Usage:
    python3 .github/scripts/check-fat-service.py        # from repo root
    py .github/scripts/check-fat-service.py             # Windows launcher
Exit code 0 = architecture + clean code OK, 1 = violation.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend" / "SmartSolarMicrogrid.Api"
CONTROLLERS = BACKEND / "Controllers"
SERVICES = BACKEND / "Services"
WEB_SRC = REPO_ROOT / "web" / "src"

# Template scaffold kept only as a health sample; real endpoints must follow
# the fat-service rules. Delete it when no longer needed.
SCAFFOLD_ALLOWLIST = {"WeatherForecastController.cs", "WeatherForecast.cs"}
# Console output is acceptable only while the host/logger is being bootstrapped.
BOOTSTRAP_ALLOWLIST = {"Program.cs", "AccountSetup.cs"}

MAX_CONTROLLER_LINES = 200
MAX_TSX_LINES = 400

# Data-access / crypto / token primitives that must never appear in a
# thin controller. Business logic belongs in Services/.
FORBIDDEN_IN_CONTROLLERS = [
    r"MongoDB",
    r"IMongo(Collection|Database)",
    r"Builders\s*<",
    r"\.Find\s*\(",
    r"InsertOne(Async)?",
    r"ReplaceOne(Async)?",
    r"Delete(One|Many)(Async)?",
    r"Aggregate\s*\(",
    r"BCrypt",
    r"PasswordHasher",
    r"JwtSecurityToken\s*\(",
    r"SigningCredentials",
    r"JwtSecurityTokenHandler",
    r"SqlConnection",
    r"DbContext",
    r"new\s+HttpClient\s*\(",
]

HTTP_VERB = re.compile(r"\[Http(Get|Post|Put|Patch|Delete)[^\]]*\]")
SERVICE_CALL = re.compile(r"_\w+\s*\.")
INJECTED_SERVICE = re.compile(
    r"private\s+readonly\s+\w*(Service|Interface)\b|\b\w*(Service|Interface)\s+\w+\s*[,)]"
)

failures: list[str] = []
warnings: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  [FAIL] {msg}")


def warn(msg: str) -> None:
    warnings.append(msg)
    print(f"  [WARN] {msg}")


def ok(msg: str) -> None:
    print(f"  [PASS] {msg}")


def check_backend() -> None:
    print("Backend: thin controllers / fat services")
    if not CONTROLLERS.is_dir():
        fail(f"Controllers directory missing: {CONTROLLERS}")
        return
    if not SERVICES.is_dir():
        fail(f"Services directory missing: {SERVICES}")
        return

    controllers = sorted(CONTROLLERS.glob("*Controller.cs"))
    if not controllers:
        fail("No *Controller.cs files found.")
        return

    service_files = list(SERVICES.glob("*.cs"))
    if not service_files:
        fail("No service files found in Services/ (fat-service layer missing).")

    controller_loc = 0
    for ctrl in controllers:
        text = ctrl.read_text(encoding="utf-8-sig")
        lines = text.splitlines()
        name = ctrl.name
        if name in SCAFFOLD_ALLOWLIST:
            print(f"  [SKIP] {name} (template scaffold, exempt from service rules)")
            continue

        controller_loc += len([ln for ln in lines if ln.strip()])

        for pattern in FORBIDDEN_IN_CONTROLLERS:
            if re.search(pattern, text):
                fail(
                    f"{name}: contains '{pattern}' — move data-access/crypto/token "
                    f"logic into Services/."
                )

        if "[ApiController]" not in text:
            fail(f"{name}: missing [ApiController] attribute.")

        if not INJECTED_SERVICE.search(text):
            fail(
                f"{name}: does not inject a *Service/*Interface — "
                f"controllers must delegate to the fat-service layer."
            )

        if len(lines) > MAX_CONTROLLER_LINES:
            fail(
                f"{name}: {len(lines)} lines exceeds thin-controller limit "
                f"({MAX_CONTROLLER_LINES}). Move logic into Services/."
            )

        actions = len(HTTP_VERB.findall(text))
        calls = len(SERVICE_CALL.findall(text))
        if actions == 0:
            fail(f"{name}: no [Http*] actions found.")
        elif calls < actions:
            fail(
                f"{name}: {actions} action(s) but only {calls} service call(s) — "
                f"every action must delegate to an injected service."
            )
        else:
            ok(f"{name}: thin ({len(lines)} lines, {actions} actions -> service)")

    service_loc = sum(
        len([ln for ln in f.read_text(encoding="utf-8-sig").splitlines() if ln.strip()])
        for f in service_files
    )
    if controller_loc and service_loc < controller_loc:
        fail(
            f"Services/ ({service_loc} LOC) is smaller than Controllers/ "
            f"({controller_loc} LOC) — business logic is leaking into controllers."
        )
    else:
        ok(f"Services/ ({service_loc} LOC) >= Controllers/ ({controller_loc} LOC)")


def check_backend_clean() -> None:
    print("Backend: clean-code rules")
    checked = 0
    for path in list(CONTROLLERS.glob("*.cs")) + list(SERVICES.glob("*.cs")):
        if path.name in SCAFFOLD_ALLOWLIST:
            continue
        text = path.read_text(encoding="utf-8-sig")
        checked += 1
        if "Console.Write" in text and path.name not in BOOTSTRAP_ALLOWLIST:
            fail(
                f"{path.name}: Console.Write* outside bootstrap files — "
                f"use ILogger instead."
            )
        for marker in ("TODO", "FIXME", "HACK"):
            if marker in text:
                warn(f"{path.name}: contains '{marker}' marker.")
    if not any(f.startswith("  [FAIL]") and "Console.Write" in f for f in failures):
        ok(f"No Console.Write* outside bootstrap ({checked} files scanned)")


def check_frontend() -> None:
    print("Frontend: fat api-service layer / thin components")
    if not WEB_SRC.is_dir():
        fail(f"web/src directory missing: {WEB_SRC}")
        return

    api = WEB_SRC / "api.ts"
    if not api.is_file() or "export async function api" not in api.read_text(
        encoding="utf-8-sig"
    ):
        fail("web/src/api.ts must export the shared `api()` service helper.")
    else:
        ok("web/src/api.ts service helper present")

    service_dirs = {api.parent}
    services_dir = WEB_SRC / "services"
    if services_dir.is_dir():
        service_dirs.add(services_dir)

    http_re = re.compile(r"(?<![\w.])fetch\s*\(|axios\.|XMLHttpRequest")
    for path in sorted(WEB_SRC.rglob("*.ts")) + sorted(WEB_SRC.rglob("*.tsx")):
        if "node_modules" in path.parts or "dist" in path.parts:
            continue
        text = path.read_text(encoding="utf-8-sig")
        rel = path.relative_to(REPO_ROOT).as_posix()
        if http_re.search(text) and path.parent not in service_dirs and path != api:
            fail(
                f"{rel}: raw HTTP call outside the api-service layer — "
                f"use src/api.ts instead."
            )
        if path.suffix == ".tsx" and len(text.splitlines()) > MAX_TSX_LINES:
            fail(
                f"{rel}: exceeds component size limit ({MAX_TSX_LINES} lines). "
                f"Split the component or move logic into the service layer."
            )

    tsx_count = len(list(WEB_SRC.rglob("*.tsx")))
    ok(f"All raw HTTP confined to api-service layer ({tsx_count} components checked)")


def check_frontend_clean() -> None:
    print("Frontend: clean-code rules")
    dirty: list[str] = []
    for path in list(WEB_SRC.rglob("*.ts")) + list(WEB_SRC.rglob("*.tsx")):
        if "node_modules" in path.parts or "dist" in path.parts:
            continue
        text = path.read_text(encoding="utf-8-sig")
        rel = path.relative_to(REPO_ROOT).as_posix()
        if re.search(r"console\.(log|warn|error|debug)\s*\(|\bdebugger\b", text):
            dirty.append(rel)
        for marker in ("TODO", "FIXME", "HACK"):
            if marker in text:
                warn(f"{rel}: contains '{marker}' marker.")
    if dirty:
        for rel in dirty:
            fail(f"{rel}: console.*/debugger leftover — remove before merge.")
    else:
        ok("No console.*/debugger leftovers in web/src")


def main() -> int:
    print("Fat-service architecture + clean-code guard")
    print(f"Repo: {REPO_ROOT}\n")
    check_backend()
    print()
    check_backend_clean()
    print()
    check_frontend()
    print()
    check_frontend_clean()
    print()
    print(f"Result: {len(failures)} failure(s), {len(warnings)} warning(s).")
    if failures:
        print("Architecture/clean-code gate FAILED.")
        return 1
    print("Architecture/clean-code gate PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
