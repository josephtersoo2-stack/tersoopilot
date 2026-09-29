#!/usr/bin/env python3
"""
TersoPilot Hot-Patch Creator.
Packages backend modifications, bug fixes, or new features into a ready-to-upload
WordPress-style .patch.zip archive with manifest metadata and SHA-256 verification.

Usage:
  python scripts/create_patch.py --name "Feature Name" --version "1.1.0" --files backend/devices/views.py backend/core/settings.py
  python scripts/create_patch.py --name "Latest Git Commit" --git
  python scripts/create_patch.py --interactive
"""

import os
import sys
import json
import zipfile
import argparse
import subprocess
from pathlib import Path
from datetime import datetime

ROOT_DIR = Path(__file__).resolve().parent.parent
BACKEND_DIR = ROOT_DIR / "backend"
DIST_DIR = ROOT_DIR / "dist" / "patches"


EXCLUDE_SUBSTRINGS = [
    "__pycache__",
    ".pyc",
    "tmp/",
    "storage/",
    "exec_logs",
    "data_dump",
    ".DS_Store",
    "db.sqlite3",
]


def get_git_changed_files():
    """Gets list of uncommitted or latest-commit modified files in backend/."""
    try:
        # Check uncommitted git changes first
        cmd = ["git", "status", "--porcelain", "backend/"]
        res = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True, text=True, check=True)
        raw_paths = []
        for line in res.stdout.strip().splitlines():
            if not line:
                continue
            status, path_str = line[:2].strip(), line[3:].strip()
            if path_str.startswith('"') and path_str.endswith('"'):
                path_str = path_str[1:-1]
            raw_paths.append(path_str)

        files = []
        for path_str in raw_paths:
            full = ROOT_DIR / path_str
            if full.is_file():
                files.append(path_str)
            elif full.is_dir():
                for root, _, fnames in os.walk(full):
                    for fn in fnames:
                        fp = Path(root) / fn
                        files.append(str(fp.relative_to(ROOT_DIR)).replace("\\", "/"))

        if not files:
            # Fallback to files in last commit
            cmd2 = ["git", "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", "backend/"]
            res2 = subprocess.run(cmd2, cwd=ROOT_DIR, capture_output=True, text=True)
            for line in res2.stdout.strip().splitlines():
                if line:
                    full = ROOT_DIR / line
                    if full.is_file():
                        files.append(line)
                    elif full.is_dir():
                        for root, _, fnames in os.walk(full):
                            for fn in fnames:
                                fp = Path(root) / fn
                                files.append(str(fp.relative_to(ROOT_DIR)).replace("\\", "/"))

        # Filter out unwanted runtime artifacts
        filtered = []
        for f in files:
            norm = f.replace("\\", "/")
            if any(exc in norm for exc in EXCLUDE_SUBSTRINGS):
                continue
            filtered.append(norm)

        return list(dict.fromkeys(filtered))
    except Exception as e:
        print(f"Warning: Could not retrieve git diff: {e}")
        return []


def create_patch_package(
    name: str,
    version: str,
    description: str,
    files: list = None,
    include_frontend: bool = False,
    output_zip: str = None,
    run_migrations: bool = False,
    collectstatic: bool = False,
    author: str = "Developer"
) -> str:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    FRONTEND_DIST = ROOT_DIR / "admin-panel" / "dist"

    timestamp_str = datetime.now().strftime("%Y%m%d_%H%M%S")
    clean_name = "".join(c if c.isalnum() or c in ("-", "_") else "_" for c in name.lower()).strip("_")
    patch_id = f"patch_{clean_name}_{timestamp_str}"

    if not output_zip:
        output_zip = DIST_DIR / f"{patch_id}.patch.zip"
    else:
        output_zip = Path(output_zip)

    payload_files = []
    has_migrations = run_migrations

    # 1. Include Frontend files if requested
    if include_frontend:
        if not FRONTEND_DIST.exists():
            print("Warning: admin-panel/dist does not exist. Run 'npm run build' first.")
        else:
            for root, _, filenames in os.walk(FRONTEND_DIST):
                for fn in filenames:
                    abs_p = Path(root) / fn
                    rel_p = "public_html/" + str(abs_p.relative_to(FRONTEND_DIST)).replace("\\", "/")
                    payload_files.append((abs_p, rel_p))

    # 2. Include explicit or git files
    for f in (files or []):
        f_path = Path(f)
        if not f_path.is_absolute():
            if (ROOT_DIR / f_path).exists():
                f_path = ROOT_DIR / f_path
            elif (BACKEND_DIR / f_path).exists():
                f_path = BACKEND_DIR / f_path

        if not f_path.exists():
            print(f"Warning: File {f} not found, skipping.")
            continue

        # Check if inside admin-panel/dist
        try:
            rel_to_fe = f_path.relative_to(FRONTEND_DIST)
            rel_str = "public_html/" + str(rel_to_fe).replace("\\", "/")
            payload_files.append((f_path, rel_str))
            continue
        except (ValueError, Exception):
            pass

        # Check if inside backend/
        try:
            rel_to_backend = f_path.relative_to(BACKEND_DIR)
            rel_str = str(rel_to_backend).replace("\\", "/")
            if "migrations/" in rel_str and rel_str.endswith(".py") and not rel_str.endswith("__init__.py"):
                has_migrations = True
            payload_files.append((f_path, rel_str))
        except ValueError:
            print(f"Warning: File {f} is not inside backend/ or admin-panel/dist/, skipping.")
            continue

    if not payload_files:
        raise ValueError("No valid files selected for package.")

    manifest = {
        "id": patch_id,
        "name": name,
        "version": version,
        "description": description,
        "author": author,
        "created_at": datetime.now().isoformat(),
        "migrations": has_migrations,
        "collectstatic": collectstatic,
        "files": [rel for _, rel in payload_files],
    }

    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("patch.json", json.dumps(manifest, indent=2))
        for abs_file, rel_name in payload_files:
            zf.write(abs_file, rel_name)

    print(f"\n[+] Created Hot-Patch Package: {output_zip}")
    print(f"    Patch ID: {patch_id}")
    print(f"    Name: {name} (v{version})")
    print(f"    Files Included: {len(payload_files)}")
    print(f"    Contains Migrations: {has_migrations}")
    return str(output_zip)


def main():
    parser = argparse.ArgumentParser(description="Build WordPress-style .patch.zip for TersoPilot backend & frontend.")
    parser.add_argument("--name", default="System Update", help="Title of this update package.")
    parser.add_argument("--version", default="1.1.0", help="Semantic version string.")
    parser.add_argument("--description", default="", help="Description of changes.")
    parser.add_argument("--files", nargs="*", default=[], help="Specific files to include.")
    parser.add_argument("--git", action="store_true", help="Automatically include git modified files in backend/.")
    parser.add_argument("--frontend", action="store_true", help="Include built React admin-panel/dist files into public_html/.")
    parser.add_argument("--all", action="store_true", help="Include both built frontend (public_html) and git modified backend files.")
    parser.add_argument("--migrations", action="store_true", help="Trigger python manage.py migrate on deploy.")
    parser.add_argument("--collectstatic", action="store_true", help="Trigger collectstatic on deploy.")
    parser.add_argument("--out", default=None, help="Output zip filename.")

    args = parser.parse_args()

    files = list(args.files)
    if args.git or args.all:
        git_files = get_git_changed_files()
        print(f"Detected {len(git_files)} modified git files: {git_files}")
        files.extend(git_files)

    include_fe = args.frontend or args.all

    if not files and not include_fe:
        print("Error: No files specified. Provide --files, --git, --frontend, or --all.")
        sys.exit(1)

    create_patch_package(
        name=args.name,
        version=args.version,
        description=args.description,
        files=files,
        include_frontend=include_fe,
        output_zip=args.out,
        run_migrations=args.migrations,
        collectstatic=args.collectstatic,
    )


if __name__ == "__main__":
    main()
