#!/usr/bin/env python3
"""
TersoPilot Namecheap Packaging Script.
Builds the React Admin Panel, collects MySQL-ready Django backend,
and packages clean, production-ready zip files for Namecheap cPanel upload.
"""

import os
import sys
import shutil
import zipfile
import subprocess
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
BACKEND_DIR = ROOT_DIR / "backend"
FRONTEND_DIR = ROOT_DIR / "admin-panel"
DIST_DIR = ROOT_DIR / "dist" / "namecheap"

# Backend items to exclude from production zip
EXCLUDE_DIRS = {
    ".venv",
    "__pycache__",
    ".git",
    "tmp",
    "scratch",
}

EXCLUDE_FILES = {
    "db.sqlite3",
    "db.sqlite3-journal",
    ".env",
    ".DS_Store",
    "exec_logs.txt",
}

EXCLUDE_EXTENSIONS = {
    ".pyc",
    ".pyo",
    ".pyd",
    ".log",
}


def build_react_admin():
    print("\n[1/3] Building React Admin Panel for Production...")
    npm_cmd = "npm.cmd" if sys.platform == "win32" else "npm"
    res = subprocess.run([npm_cmd, "run", "build"], cwd=FRONTEND_DIR, text=True)
    if res.returncode != 0:
        raise RuntimeError("React Admin build failed! Check npm run build output.")

    # Ensure .htaccess exists in dist/
    dist_htaccess = FRONTEND_DIR / "dist" / ".htaccess"
    src_htaccess = FRONTEND_DIR / "public" / ".htaccess"
    if src_htaccess.exists():
        shutil.copy2(src_htaccess, dist_htaccess)
    print("    React Admin built successfully into admin-panel/dist/")


def package_backend(output_zip: Path):
    print("\n[2/3] Packaging MySQL-Ready Django Backend...")
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    file_count = 0
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(BACKEND_DIR):
            # Prune excluded directories
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS and not d.startswith(".")]

            for file in files:
                if file in EXCLUDE_FILES or any(file.endswith(ext) for ext in EXCLUDE_EXTENSIONS):
                    continue

                full_path = Path(root) / file
                rel_path = full_path.relative_to(BACKEND_DIR)
                zf.write(full_path, str(rel_path).replace("\\", "/"))
                file_count += 1

        # Automatically bundle the pre-configured production .env with user credentials
        prod_env = BACKEND_DIR / ".env.production"
        if prod_env.exists():
            zf.write(prod_env, ".env")
            file_count += 1
            print("    Bundled pre-configured production .env into backend zip")

    print(f"    Packaged {file_count} files into: {output_zip.name}")


def package_public_html(output_zip: Path):
    print("\n[3/3] Packaging React public_html Frontend...")
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    dist_dir = FRONTEND_DIR / "dist"
    if not dist_dir.exists():
        raise RuntimeError("Frontend dist directory missing. Run build first.")

    file_count = 0
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(dist_dir):
            for file in files:
                full_path = Path(root) / file
                rel_path = full_path.relative_to(dist_dir)
                zf.write(full_path, str(rel_path).replace("\\", "/"))
                file_count += 1

    print(f"    Packaged {file_count} files into: {output_zip.name}")


def create_all_in_one_bundle(bundle_zip: Path, backend_zip: Path, frontend_zip: Path):
    print("\n[+] Creating Complete All-in-One Namecheap Deployment Bundle...")
    with zipfile.ZipFile(bundle_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        # Add backend zip
        zf.write(backend_zip, "namecheap_backend.zip")
        # Add frontend zip
        zf.write(frontend_zip, "namecheap_public_html.zip")

        # Add MySQL migration script if generated
        sql_file = DIST_DIR / "migrate_to_mysql.sql"
        if sql_file.exists():
            zf.write(sql_file, "migrate_to_mysql.sql")

        # Add database data dump json
        data_dump = DIST_DIR / "data_dump_clean.json"
        if data_dump.exists():
            zf.write(data_dump, "data_dump_clean.json")

        # Add quickstart setup instructions
        guide_path = ROOT_DIR / "NAMECHEAP_DEPLOYMENT_GUIDE.md"
        if guide_path.exists():
            zf.write(guide_path, "NAMECHEAP_DEPLOYMENT_GUIDE.md")

    print(f"    Master Bundle created: {bundle_zip.name} ({(bundle_zip.stat().st_size / (1024*1024)):.2f} MB)")


def main():
    print("=" * 65)
    print("  TersoPilot Namecheap Production Packaging Tool")
    print("  MySQL Backend + React Frontend + Hot-Patch System")
    print("=" * 65)

    DIST_DIR.mkdir(parents=True, exist_ok=True)

    backend_zip = DIST_DIR / "namecheap_backend.zip"
    frontend_zip = DIST_DIR / "namecheap_public_html.zip"
    bundle_zip = DIST_DIR / "namecheap_complete_bundle.zip"

    try:
        build_react_admin()
        package_backend(backend_zip)
        package_public_html(frontend_zip)
        create_all_in_one_bundle(bundle_zip, backend_zip, frontend_zip)

        apk_src = ROOT_DIR / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        apk_dest = DIST_DIR / "TersooPilot-release.apk"
        if apk_src.exists():
            shutil.copy2(apk_src, apk_dest)
            print(f"    Copied Release APK to: {apk_dest.name} ({(apk_dest.stat().st_size / (1024*1024)):.2f} MB)")

        print("\n" + "=" * 65)
        print("  PACKAGE CREATION SUCCESSFUL!")
        print("=" * 65)
        print(f"1. Backend Zip:     {backend_zip}")
        print(f"2. Frontend Zip:    {frontend_zip}")
        print(f"3. Complete Bundle: {bundle_zip}")
        if apk_dest.exists():
            print(f"4. Release APK:     {apk_dest}")
        print("\nRead NAMECHEAP_DEPLOYMENT_GUIDE.md for step-by-step cPanel upload instructions.")
    except Exception as e:
        print(f"\n[-] Packaging failed: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
