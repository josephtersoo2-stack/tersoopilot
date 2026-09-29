import os
import sys
import json
import shutil
import hashlib
import zipfile
import logging
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Any, Optional, Tuple
from django.conf import settings
from django.core.management import call_command
from django.utils import timezone

logger = logging.getLogger("system_updater")

UPDATES_DIR = os.path.join(settings.BASE_DIR, "storage", "updates")
ROLLBACK_VAULT = os.path.join(UPDATES_DIR, "rollback")
STAGING_DIR = os.path.join(UPDATES_DIR, "staging")
LEDGER_PATH = os.path.join(UPDATES_DIR, "ledger.json")

# Critical paths that should NEVER be overwritten by standard patches
PROTECTED_PATHS = {
    ".env",
    "db.sqlite3",
    "storage/updates",
    ".git",
    ".venv",
}


def compute_file_hash(filepath: str) -> str:
    """Computes SHA-256 hash of a file."""
    hasher = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def touch_passenger_restart():
    """
    Triggers an instant zero-downtime application reload for Namecheap cPanel
    and Phusion Passenger environments by touching tmp/restart.txt.
    """
    try:
        tmp_dir = os.path.join(settings.BASE_DIR, "tmp")
        os.makedirs(tmp_dir, exist_ok=True)
        restart_txt = os.path.join(tmp_dir, "restart.txt")
        Path(restart_txt).touch(exist_ok=True)
        logger.info("Touched Passenger reload trigger: %s", restart_txt)

        # Also touch passenger_wsgi.py if it exists
        passenger_wsgi = os.path.join(settings.BASE_DIR, "passenger_wsgi.py")
        if os.path.exists(passenger_wsgi):
            Path(passenger_wsgi).touch(exist_ok=True)
    except Exception as e:
        logger.warning("Could not touch restart trigger: %s", e)


def get_public_html_dirs() -> List[str]:
    """
    Finds all target public_html and subdomain web directories:
    1. Settings or Environment override: PUBLIC_HTML_DIR / PUBLIC_HTML_PATH
    2. Sibling directory: ../public_html (standard cPanel main domain)
    3. Sibling directories for subdomains: e.g. ../tersoopilot.tersoo.name.ng, ../tersoopilot, or any sibling matching *tersoopilot*
    4. Subdomain directories inside public_html: ../public_html/tersoopilot
    5. Child directory: ./public_html
    """
    dirs = []
    env_dir = getattr(settings, "PUBLIC_HTML_DIR", None) or os.environ.get("PUBLIC_HTML_DIR") or os.environ.get("PUBLIC_HTML_PATH")
    if env_dir:
        dirs.append(os.path.normpath(env_dir))

    parent_dir = os.path.dirname(settings.BASE_DIR)
    parent_public = os.path.normpath(os.path.join(parent_dir, "public_html"))
    if os.path.exists(parent_public):
        dirs.append(parent_public)

    # Check for subdomains in home directory (e.g. /home/user/tersoopilot.tersoo.name.ng)
    if os.path.exists(parent_dir):
        try:
            for entry in os.listdir(parent_dir):
                full_p = os.path.normpath(os.path.join(parent_dir, entry))
                if os.path.isdir(full_p) and full_p != os.path.normpath(settings.BASE_DIR):
                    low = entry.lower()
                    if "tersoopilot" in low and not low.endswith("backend") and not low.endswith("_backend"):
                        dirs.append(full_p)
                    elif os.path.exists(os.path.join(full_p, "index.html")) and not os.path.exists(os.path.join(full_p, "manage.py")):
                        dirs.append(full_p)
        except Exception:
            pass

    # Check inside public_html for subfolders
    if os.path.exists(parent_public):
        try:
            for sub in ("tersoopilot", "tersoopilot.tersoo.name.ng", "app", "dashboard"):
                sub_p = os.path.normpath(os.path.join(parent_public, sub))
                if os.path.exists(sub_p):
                    dirs.append(sub_p)
        except Exception:
            pass

    child_public = os.path.normpath(os.path.join(settings.BASE_DIR, "public_html"))
    if os.path.exists(child_public):
        dirs.append(child_public)

    # Deduplicate while preserving order
    unique_dirs = []
    for d in dirs:
        if d not in unique_dirs:
            unique_dirs.append(d)

    if not unique_dirs:
        unique_dirs.append(parent_public)

    return unique_dirs


def get_public_html_dir() -> str:
    """Returns primary public_html dir for backwards compatibility."""
    return get_public_html_dirs()[0]


def resolve_destination_paths(rel_path: str, is_frontend: bool = False) -> List[str]:
    """
    Resolves the target destination paths on disk for an extracted file.
    For frontend files, returns ALL target public_html and subdomain directories.
    For backend files, returns a list containing single settings.BASE_DIR path.
    """
    norm = os.path.normpath(rel_path).replace("\\", "/")
    if norm.startswith("public_html/"):
        sub = norm[len("public_html/"):]
        return [os.path.normpath(os.path.join(d, sub)) for d in get_public_html_dirs()]
    if is_frontend and (norm == "index.html" or norm.startswith("assets/") or norm in (".htaccess", "favicon.ico", "robots.txt")):
        return [os.path.normpath(os.path.join(d, norm)) for d in get_public_html_dirs()]
    return [os.path.normpath(os.path.join(settings.BASE_DIR, rel_path))]


def resolve_destination_path(rel_path: str, is_frontend: bool = False) -> str:
    """Resolves primary destination path for backwards compatibility."""
    return resolve_destination_paths(rel_path, is_frontend)[0]


class PatchEngine:
    """
    Advanced Hot-Patching & Rollback Engine.
    Enables WordPress-style zip package deployment with:
    1. Pre-flight Zip-Slip security validation
    2. Atomic shadow snapshots of overwritten files
    3. Dedicated versioned rollback vaults
    4. Migration & post-deploy hooks
    5. Clean surgical undo without collateral damage to other files
    6. cPanel / Passenger instant reload integration
    """

    @classmethod
    def _read_ledger(cls) -> List[Dict[str, Any]]:
        os.makedirs(UPDATES_DIR, exist_ok=True)
        if os.path.exists(LEDGER_PATH):
            try:
                with open(LEDGER_PATH, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                return []
        return []

    @classmethod
    def _write_ledger(cls, ledger: List[Dict[str, Any]]):
        os.makedirs(UPDATES_DIR, exist_ok=True)
        with open(LEDGER_PATH, "w", encoding="utf-8") as f:
            json.dump(ledger, f, indent=2)

    @classmethod
    def apply_patch(cls, zip_file_or_path, applied_by: str = "admin") -> Tuple[Any, Dict[str, Any]]:
        """
        Applies a hot-patch zip archive:
        - Validates manifest
        - Creates shadow snapshot of overwritten files in rollback vault
        - Deploys new/updated files
        - Runs migrations if requested
        - Triggers Passenger restart
        - Saves ledger & SystemPatch model
        """
        os.makedirs(ROLLBACK_VAULT, exist_ok=True)
        os.makedirs(STAGING_DIR, exist_ok=True)

        is_temp_file = False
        if hasattr(zip_file_or_path, "read"):
            # UploadedFile / BytesIO stream
            temp_zip_path = os.path.join(STAGING_DIR, f"incoming_{int(datetime.now().timestamp())}.zip")
            with open(temp_zip_path, "wb") as out:
                if hasattr(zip_file_or_path, "chunks"):
                    for chunk in zip_file_or_path.chunks():
                        out.write(chunk)
                else:
                    out.write(zip_file_or_path.read())
            zip_path = temp_zip_path
            is_temp_file = True
        else:
            zip_path = str(zip_file_or_path)

        try:
            return cls._process_patch_zip(zip_path, applied_by)
        finally:
            if is_temp_file and os.path.exists(zip_path):
                try:
                    os.remove(zip_path)
                except Exception:
                    pass

    @classmethod
    def _process_patch_zip(cls, zip_path: str, applied_by: str) -> Tuple[Any, Dict[str, Any]]:
        if not zipfile.is_zipfile(zip_path):
            raise ValueError("The uploaded file is not a valid zip archive.")

        with zipfile.ZipFile(zip_path, "r") as zf:
            # 1. Locate manifest (patch.json or manifest.json)
            manifest_entry = None
            for name in zf.namelist():
                base = os.path.basename(name)
                if base in ("patch.json", "manifest.json", "update.json") and not name.startswith("__MACOSX"):
                    manifest_entry = name
                    break

            is_pure_frontend = False
            all_non_dir = [i.filename for i in zf.infolist() if not i.is_dir() and not i.filename.startswith("__MACOSX")]

            if not manifest_entry:
                # WordPress-style auto-detection: if no patch.json provided, synthesize manifest
                is_pure_frontend = any(n in ("index.html", "public_html/index.html") or n.startswith("assets/") for n in all_non_dir)
                timestamp_str = datetime.now().strftime("%Y%m%d_%H%M%S")
                manifest = {
                    "id": f"auto_update_{timestamp_str}",
                    "name": "Frontend Web UI (React Admin)" if is_pure_frontend else "System Hot-Patch Update",
                    "version": datetime.now().strftime("%Y.%m.%d"),
                    "description": "WordPress-style auto-detected package applied via System Updater",
                    "author": applied_by or "Admin",
                    "migrations": False,
                    "collectstatic": False,
                    "is_frontend": is_pure_frontend,
                }
            else:
                try:
                    manifest_content = zf.read(manifest_entry).decode("utf-8")
                    manifest = json.loads(manifest_content)
                except Exception as e:
                    raise ValueError(f"Invalid JSON in {manifest_entry}: {e}")

            name = manifest.get("name", "System Patch")
            version = manifest.get("version", "1.0.0")
            description = manifest.get("description", "")
            author = manifest.get("author", "System")
            run_migrations = manifest.get("migrations", False)
            collectstatic = manifest.get("collectstatic", False)
            is_frontend_pkg = manifest.get("is_frontend", is_pure_frontend)

            # Generate unique patch_id
            timestamp_str = datetime.now().strftime("%Y%m%d_%H%M%S")
            raw_id = manifest.get("id", f"patch_{timestamp_str}")
            clean_id = "".join(c if c.isalnum() or c in ("-", "_") else "_" for c in str(raw_id).lower()).strip("_")
            patch_id = f"{clean_id}_{timestamp_str}"

            patch_vault = os.path.join(ROLLBACK_VAULT, patch_id)
            backup_files_dir = os.path.join(patch_vault, "backup")
            os.makedirs(backup_files_dir, exist_ok=True)

            # Determine package root prefix
            prefix = ""
            if manifest_entry and "/" in manifest_entry:
                prefix = manifest_entry.rsplit("/", 1)[0] + "/"

            # 2. Pre-flight Security and File Validation
            file_entries = []
            for item in zf.infolist():
                if item.filename.startswith("__MACOSX") or item.is_dir():
                    continue
                rel_path = item.filename
                if prefix and rel_path.startswith(prefix):
                    rel_path = rel_path[len(prefix):]
                if not rel_path or rel_path in ("patch.json", "manifest.json", "update.json"):
                    continue

                # Security check: Zip Slip traversal
                normalized = os.path.normpath(rel_path)
                if normalized.startswith("..") or os.path.isabs(normalized):
                    raise ValueError(f"Security error: Zip slip path traversal detected in '{rel_path}'")

                # Security check: Protected files
                for protected in PROTECTED_PATHS:
                    if normalized == protected or normalized.startswith(protected + os.sep) or normalized.startswith(protected + "/"):
                        raise ValueError(f"Security error: Patch attempts to modify protected path '{normalized}'")

                file_entries.append((item, normalized))

            if not file_entries:
                raise ValueError("Patch package contains no payload files to deploy.")

            # 3. Create Shadow Snapshot of existing files for Rollback Vault
            rollback_actions = []
            files_modified_list = []

            for zip_item, rel_path in file_entries:
                target_paths = resolve_destination_paths(rel_path, is_frontend=is_frontend_pkg)
                files_modified_list.append(rel_path)

                for idx, target_abs_path in enumerate(target_paths):
                    action_key = f"{rel_path}__dst{idx}" if len(target_paths) > 1 else rel_path
                    if os.path.exists(target_abs_path) and os.path.isfile(target_abs_path):
                        # Existing file will be overwritten: back it up
                        orig_hash = compute_file_hash(target_abs_path)
                        backup_target = os.path.normpath(os.path.join(backup_files_dir, action_key))
                        os.makedirs(os.path.dirname(backup_target), exist_ok=True)
                        shutil.copy2(target_abs_path, backup_target)

                        rollback_actions.append({
                            "path": rel_path,
                            "target_abs": target_abs_path,
                            "action": "RESTORE",
                            "original_sha256": orig_hash,
                            "backup_rel": action_key,
                        })
                    else:
                        # New file will be created: record for deletion on undo
                        rollback_actions.append({
                            "path": rel_path,
                            "target_abs": target_abs_path,
                            "action": "DELETE",
                        })

            # Save Rollback Vault Manifest
            vault_manifest = {
                "patch_id": patch_id,
                "name": name,
                "version": version,
                "author": author,
                "applied_at": datetime.now().isoformat(),
                "applied_by": applied_by,
                "manifest_original": manifest,
                "actions": rollback_actions,
            }
            with open(os.path.join(patch_vault, "manifest.json"), "w", encoding="utf-8") as vf:
                json.dump(vault_manifest, vf, indent=2)

            # 4. Extract and Deploy Files Atomically
            for zip_item, rel_path in file_entries:
                target_paths = resolve_destination_paths(rel_path, is_frontend=is_frontend_pkg)
                for target_abs_path in target_paths:
                    os.makedirs(os.path.dirname(target_abs_path), exist_ok=True)
                    with zf.open(zip_item) as src, open(target_abs_path, "wb") as dst:
                        shutil.copyfileobj(src, dst)

            # 5. Execute Post-deploy Hooks
            if run_migrations:
                try:
                    logger.info("Executing Django migrations requested by patch...")
                    call_command("migrate", interactive=False)
                except Exception as e:
                    logger.error("Migration failed during patch apply: %s", e)

            if collectstatic:
                try:
                    logger.info("Running collectstatic requested by patch...")
                    call_command("collectstatic", interactive=False, clear=False)
                except Exception as e:
                    logger.warning("Collectstatic error during patch apply: %s", e)

            # 6. Trigger Passenger / cPanel Reload
            touch_passenger_restart()

            # 7. Record to DB model & Disk Ledger
            from .models import SystemPatch
            patch_record = SystemPatch.objects.create(
                patch_id=patch_id,
                name=name,
                version=version,
                description=description,
                author=author,
                status=SystemPatch.STATUS_ACTIVE,
                backup_dir=patch_vault,
                files_modified=files_modified_list,
                manifest_data=manifest,
                applied_by=applied_by,
            )

            ledger = cls._read_ledger()
            ledger.insert(0, {
                "patch_id": patch_id,
                "name": name,
                "version": version,
                "status": "ACTIVE",
                "applied_at": datetime.now().isoformat(),
                "file_count": len(files_modified_list),
                "vault": patch_vault,
            })
            cls._write_ledger(ledger)

            return patch_record, vault_manifest

    @classmethod
    def rollback_patch(cls, patch_id: Optional[str] = None, dry_run: bool = False) -> Dict[str, Any]:
        """
        Performs a clean, surgical rollback of a patch:
        - Restores overwritten files from the shadow snapshot vault
        - Safely removes newly added files
        - Leaves untouched files 100% intact
        - Reloads Passenger application
        - Marks record as ROLLED_BACK
        """
        from .models import SystemPatch

        # Determine target patch
        if patch_id:
            patch_record = SystemPatch.objects.filter(patch_id=patch_id).first()
        else:
            patch_record = SystemPatch.objects.filter(status=SystemPatch.STATUS_ACTIVE).order_by("-applied_at").first()

        target_patch_id = patch_record.patch_id if patch_record else patch_id
        if not target_patch_id:
            # Check ledger fallback
            ledger = cls._read_ledger()
            for entry in ledger:
                if entry.get("status") == "ACTIVE":
                    target_patch_id = entry.get("patch_id")
                    break

        if not target_patch_id:
            raise ValueError("No active patch found to roll back.")

        patch_vault = os.path.join(ROLLBACK_VAULT, target_patch_id)
        vault_manifest_path = os.path.join(patch_vault, "manifest.json")

        if not os.path.exists(vault_manifest_path):
            raise FileNotFoundError(f"Rollback vault manifest missing for patch '{target_patch_id}'.")

        with open(vault_manifest_path, "r", encoding="utf-8") as f:
            vault_manifest = json.load(f)

        actions = vault_manifest.get("actions", [])
        backup_dir = os.path.join(patch_vault, "backup")

        restored_files = []
        deleted_files = []
        errors = []

        for item in actions:
            rel_path = item.get("path")
            action = item.get("action")
            target_abs = item.get("target_abs") or resolve_destination_path(rel_path)
            public_dir = get_public_html_dir()

            if action == "RESTORE":
                backup_file = os.path.normpath(os.path.join(backup_dir, item.get("backup_rel", rel_path)))
                if not os.path.exists(backup_file):
                    errors.append(f"Backup file missing: {rel_path}")
                    continue
                if not dry_run:
                    os.makedirs(os.path.dirname(target_abs), exist_ok=True)
                    shutil.copy2(backup_file, target_abs)
                restored_files.append(rel_path)

            elif action == "DELETE":
                if os.path.exists(target_abs):
                    if not dry_run:
                        try:
                            os.remove(target_abs)
                            # Remove parent directories if empty (stopping at BASE_DIR or public_html)
                            parent = os.path.dirname(target_abs)
                            stop_dirs = {os.path.abspath(settings.BASE_DIR), *[os.path.abspath(d) for d in get_public_html_dirs()]}
                            while parent and os.path.abspath(parent) not in stop_dirs:
                                if not os.listdir(parent):
                                    os.rmdir(parent)
                                    parent = os.path.dirname(parent)
                                else:
                                    break
                        except Exception as e:
                            errors.append(f"Could not remove created file {rel_path}: {e}")
                    deleted_files.append(rel_path)

        if not dry_run:
            touch_passenger_restart()

            if patch_record:
                patch_record.status = SystemPatch.STATUS_ROLLED_BACK
                patch_record.rolled_back_at = timezone.now()
                patch_record.save()

            # Update ledger
            ledger = cls._read_ledger()
            for entry in ledger:
                if entry.get("patch_id") == target_patch_id:
                    entry["status"] = "ROLLED_BACK"
                    entry["rolled_back_at"] = datetime.now().isoformat()
            cls._write_ledger(ledger)

        return {
            "patch_id": target_patch_id,
            "dry_run": dry_run,
            "restored_count": len(restored_files),
            "deleted_count": len(deleted_files),
            "restored_files": restored_files,
            "deleted_files": deleted_files,
            "errors": errors,
            "status": "ROLLED_BACK" if not dry_run else "DRY_RUN_SUCCESS",
        }

    @classmethod
    def list_patches(cls) -> List[Dict[str, Any]]:
        """Lists all system patches from DB and ledger."""
        from .models import SystemPatch
        db_patches = list(SystemPatch.objects.all().values(
            "id", "patch_id", "name", "version", "status",
            "applied_at", "rolled_back_at", "applied_by", "files_modified"
        ))
        if db_patches:
            for p in db_patches:
                p["file_count"] = len(p["files_modified"]) if p["files_modified"] else 0
                if p["applied_at"]:
                    p["applied_at"] = p["applied_at"].isoformat()
                if p["rolled_back_at"]:
                    p["rolled_back_at"] = p["rolled_back_at"].isoformat()
            return db_patches
        return cls._read_ledger()
