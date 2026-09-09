"""Cross-platform development commands. Run: python scripts/workflow.py --help."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"
PYTHON = ROOT / "backend/.venv" / ("Scripts/python.exe" if WINDOWS else "bin/python")
NPM = "npm.cmd" if WINDOWS else "npm"
GRADLE = str(ROOT / ("gradlew.bat" if WINDOWS else "gradlew"))


def run(command, cwd=ROOT, env=None):
    print("Running:", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, check=True)


def backend(*args):
    if not PYTHON.exists():
        raise SystemExit("Backend environment missing. Run bootstrap first.")
    run([PYTHON, "manage.py", *args], ROOT / "backend")


def bootstrap():
    if not PYTHON.exists():
        venv.create(ROOT / "backend/.venv", with_pip=True)
    run([PYTHON, "-m", "pip", "install", "-r", "requirements.txt"], ROOT / "backend")
    run([NPM, "ci"], ROOT / "admin-panel")
    for folder in ("backend", "admin-panel"):
        target = ROOT / folder / ".env"
        if not target.exists():
            shutil.copyfile(ROOT / folder / ".env.example", target)
    print("Dependencies ready. Run migrate, then createsuperuser to create your staff account.")


def doctor():
    for name in ("python", "node", NPM, "java", "adb", "docker"):
        print(f"{name}: {shutil.which(name) or 'not on PATH'}")
    print(f"Backend environment: {PYTHON.exists()}")
    print(f"JAVA_HOME: {os.getenv('JAVA_HOME', 'not set')}")
    print(f"Android local.properties: {(ROOT / 'local.properties').exists()}")
    print("For Android, set JAVA_HOME to a JDK 21 directory and configure the Android SDK.")


def verify_backend():
    backend("check")
    backend("makemigrations", "--check", "--dry-run")
    backend("test", "--noinput")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["doctor", "bootstrap", "backend", "frontend", "migrate",
        "migration-plan", "createsuperuser", "test-backend", "test-frontend", "test-android",
        "build-android", "lint-android", "check", "check-all", "release-check"])
    parser.add_argument("--host", default="127.0.0.1", help="Explicit bind address for backend/frontend")
    args = parser.parse_args()
    cmd = args.command
    if cmd == "doctor": doctor()
    elif cmd == "bootstrap": bootstrap()
    elif cmd == "backend": backend("runserver", args.host + ":8000")
    elif cmd == "frontend": run([NPM, "run", "dev", "--", "--host", args.host], ROOT / "admin-panel")
    elif cmd == "migrate": backend("migrate")
    elif cmd == "migration-plan": backend("migrate", "--plan")
    elif cmd == "createsuperuser": backend("createsuperuser")
    elif cmd == "test-backend": verify_backend()
    elif cmd == "test-frontend":
        run([NPM, "test"], ROOT / "admin-panel")
        run([NPM, "run", "build"], ROOT / "admin-panel")
    elif cmd == "test-android": run([GRADLE, ":app:testDebugUnitTest", "--console=plain"])
    elif cmd == "build-android": run([GRADLE, ":app:assembleDebug", "--console=plain"])
    elif cmd == "lint-android": run([GRADLE, ":app:lintDebug", "--console=plain"])
    elif cmd == "release-check": backend("check", "--deploy", "--fail-level", "WARNING")
    elif cmd in ("check", "check-all"):
        verify_backend()
        run([NPM, "test"], ROOT / "admin-panel")
        run([NPM, "run", "build"], ROOT / "admin-panel")
        if cmd == "check-all": run([GRADLE, ":app:testDebugUnitTest", ":app:assembleDebug", "--console=plain"])


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode)
