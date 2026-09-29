import os
import shutil
import zipfile
import json
import logging
from pathlib import Path
from typing import Dict, List, Any, Optional
from django.conf import settings
from .models import WorkflowAddon, CustomWorkflow

logger = logging.getLogger(__name__)

ADDONS_STORAGE_DIR = os.path.join(settings.BASE_DIR, "addons_storage")


class AddonManager:
    """
    Central manager for WordPress-style Workflow Addons and Extensions.
    Handles packaging, zip extraction, installation, activation, deactivation,
    complete uninstallation, step registry, and template importing.
    """

    @classmethod
    def get_storage_path(cls, slug: str) -> str:
        os.makedirs(ADDONS_STORAGE_DIR, exist_ok=True)
        return os.path.join(ADDONS_STORAGE_DIR, slug)

    @classmethod
    def install_from_zip(cls, zip_file) -> WorkflowAddon:
        """
        Installs or updates an addon from an uploaded .zip archive file.
        Extracts files cleanly into addons_storage/<slug>/ and registers in DB.
        """
        os.makedirs(ADDONS_STORAGE_DIR, exist_ok=True)

        with zipfile.ZipFile(zip_file, "r") as zf:
            # 1. Search for manifest.json or addon.json
            manifest_filename = None
            for name in zf.namelist():
                base = os.path.basename(name)
                if base in ("manifest.json", "addon.json") and not name.startswith("__MACOSX"):
                    manifest_filename = name
                    break

            if not manifest_filename:
                raise ValueError("Addon package is missing 'manifest.json' or 'addon.json' definition file.")

            # 2. Parse and validate manifest
            with zf.open(manifest_filename) as mf:
                try:
                    manifest_data = json.load(mf)
                except Exception as e:
                    raise ValueError(f"Invalid JSON in {manifest_filename}: {str(e)}")

            slug = manifest_data.get("id") or manifest_data.get("slug")
            name = manifest_data.get("name")
            if not slug or not name:
                raise ValueError("Addon manifest must specify 'id' (or 'slug') and 'name'.")

            # Clean slug
            slug = "".join(c if c.isalnum() or c in ("-", "_") else "_" for c in slug.lower()).strip("_")
            target_dir = cls.get_storage_path(slug)

            # 3. If updating existing, remove old files cleanly first
            if os.path.exists(target_dir):
                shutil.rmtree(target_dir, ignore_errors=True)
            os.makedirs(target_dir, exist_ok=True)

            # 4. Extract safely (guard against directory traversal zip-slip)
            prefix = ""
            if "/" in manifest_filename:
                prefix = manifest_filename.rsplit("/", 1)[0] + "/"

            for member in zf.infolist():
                if member.filename.startswith("__MACOSX"):
                    continue
                rel_path = member.filename
                if prefix and rel_path.startswith(prefix):
                    rel_path = rel_path[len(prefix):]
                if not rel_path or rel_path.endswith("/"):
                    continue

                dest_path = os.path.normpath(os.path.join(target_dir, rel_path))
                if not dest_path.startswith(os.path.abspath(target_dir)):
                    raise ValueError("Security violation: Zip file contains path traversal outside destination.")

                os.makedirs(os.path.dirname(dest_path), exist_ok=True)
                with zf.open(member) as src, open(dest_path, "wb") as dst:
                    shutil.copyfileobj(src, dst)

            # Re-read manifest from extracted file to ensure canonical structure
            extracted_manifest_path = os.path.join(target_dir, "manifest.json")
            if not os.path.exists(extracted_manifest_path):
                extracted_manifest_path = os.path.join(target_dir, "addon.json")
            if os.path.exists(extracted_manifest_path):
                with open(extracted_manifest_path, "r", encoding="utf-8") as emf:
                    manifest_data = json.load(emf)

            # 5. Save or update WorkflowAddon model
            version = manifest_data.get("version", "1.0.0")
            category = manifest_data.get("category", "workflow")
            platform = manifest_data.get("platform", "YOUTUBE")
            author = manifest_data.get("author", "Antidetect Team")
            description = manifest_data.get("description", "")
            icon = manifest_data.get("icon", "Puzzle")

            addon, created = WorkflowAddon.objects.update_or_create(
                slug=slug,
                defaults={
                    "name": name,
                    "version": version,
                    "category": category,
                    "platform": platform,
                    "author": author,
                    "description": description,
                    "icon": icon,
                    "is_active": True,
                    "manifest": manifest_data,
                    "installed_dir": target_dir,
                }
            )

            logger.info("Successfully installed workflow addon '%s' (created=%s)", slug, created)
            return addon

    @classmethod
    def uninstall(cls, addon_id_or_slug: str) -> bool:
        """
        Completely removes an addon, its extracted files, and DB record.
        Leaves 0 residue in the core system.
        """
        try:
            if isinstance(addon_id_or_slug, str) and "-" in addon_id_or_slug and len(addon_id_or_slug) == 36:
                addon = WorkflowAddon.objects.get(id=addon_id_or_slug)
            else:
                addon = WorkflowAddon.objects.get(slug=addon_id_or_slug)
        except WorkflowAddon.DoesNotExist:
            return False

        slug = addon.slug
        target_dir = cls.get_storage_path(slug)

        # 1. Remove files from storage
        if os.path.exists(target_dir):
            try:
                shutil.rmtree(target_dir, ignore_errors=True)
            except Exception as e:
                logger.warning("Error deleting addon directory %s: %s", target_dir, str(e))

        # 2. Remove package file if stored
        if addon.package_file:
            try:
                addon.package_file.delete(save=False)
            except Exception:
                pass

        # 3. Delete database record
        addon.delete()
        logger.info("Uninstalled workflow addon '%s' completely.", slug)
        return True

    @classmethod
    def toggle_active(cls, addon_id: str, is_active: bool) -> Optional[WorkflowAddon]:
        try:
            addon = WorkflowAddon.objects.get(id=addon_id)
            addon.is_active = is_active
            addon.save(update_fields=["is_active", "updated_at"])
            return addon
        except WorkflowAddon.DoesNotExist:
            return None

    @classmethod
    def get_active_steps(cls) -> List[Dict[str, Any]]:
        """
        Collects all custom step definitions from all active addons
        to dynamically feed into the WorkflowBuilderHub UI.
        """
        all_steps = []
        for addon in WorkflowAddon.objects.filter(is_active=True):
            addon_manifest = addon.manifest or {}
            steps = addon_manifest.get("steps") or []
            for s in steps:
                s_copy = dict(s)
                s_copy["addon_id"] = str(addon.id)
                s_copy["addon_slug"] = addon.slug
                s_copy["addon_name"] = addon.name
                s_copy["category"] = s.get("category") or f"{addon.name} (Addon)"
                all_steps.append(s_copy)
        return all_steps

    @classmethod
    def get_addon_templates(cls, addon_id: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        Retrieves pre-built templates packaged with active addons.
        """
        qs = WorkflowAddon.objects.filter(is_active=True)
        if addon_id:
            qs = qs.filter(id=addon_id)

        templates = []
        for addon in qs:
            addon_manifest = addon.manifest or {}
            for tmpl in addon_manifest.get("templates") or []:
                t_copy = dict(tmpl)
                t_copy["addon_id"] = str(addon.id)
                t_copy["addon_name"] = addon.name
                t_copy["addon_slug"] = addon.slug
                templates.append(t_copy)
        return templates

    @classmethod
    def import_template_to_workflows(cls, addon_id: str, template_name: str) -> Optional[CustomWorkflow]:
        """
        Imports a bundled addon template into CustomWorkflow for immediate execution.
        Accepts addon UUID or slug, and template name or template id.
        """
        addon = None
        try:
            import uuid
            uuid.UUID(str(addon_id))
            addon = WorkflowAddon.objects.filter(id=addon_id).first()
        except (ValueError, TypeError, AttributeError):
            pass

        if not addon:
            addon = WorkflowAddon.objects.filter(slug=addon_id).first()

        if not addon:
            return None

        templates = addon.manifest.get("templates") or []
        target = None
        for t in templates:
            if t.get("name") == template_name or t.get("id") == template_name:
                target = t
                break
        if not target and templates:
            target = templates[0]

        if not target:
            return None

        # Build journeys from either explicit journeys array or canvas nodes
        journeys = target.get("journeys")
        if not journeys and target.get("nodes"):
            steps = []
            for n in target.get("nodes", []):
                step_dict = {
                    "type": n.get("type", "START"),
                    "label": n.get("label", "")
                }
                step_dict.update(n.get("params") or {})
                steps.append(step_dict)
            journeys = [{
                "name": target.get("name", "Main Journey"),
                "steps": steps
            }]

        workflow = CustomWorkflow.objects.create(
            name=target.get("name", f"{addon.name} Workflow"),
            platform=target.get("platform", addon.platform),
            description=target.get("description", f"Imported from {addon.name} addon"),
            journeys=journeys or [],
            settings={
                "nodes": target.get("nodes", []),
                "edges": target.get("edges", []),
                "imported_from_addon": addon.slug
            }
        )
        return workflow

    @classmethod
    def export_addon_zip(cls, addon_id: str) -> Optional[str]:
        """
        Creates a .zip archive of the addon directory for export/download.
        Returns the path to the generated zip file in a temporary scratch location.
        """
        try:
            addon = WorkflowAddon.objects.get(id=addon_id)
        except WorkflowAddon.DoesNotExist:
            return None

        target_dir = cls.get_storage_path(addon.slug)
        if not os.path.exists(target_dir):
            return None

        zip_dest = os.path.join(ADDONS_STORAGE_DIR, f"{addon.slug}_v{addon.version}.zip")
        with zipfile.ZipFile(zip_dest, "w", zipfile.ZIP_DEFLATED) as zf:
            for root, _, files in os.walk(target_dir):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, target_dir)
                    zf.write(full_path, rel_path)

        return zip_dest
