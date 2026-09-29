import os
import sys
from django.core.management.base import BaseCommand
from system_updater.engine import PatchEngine


class Command(BaseCommand):
    help = "Applies a system hot-patch/update zip package from the command line."

    def add_arguments(self, parser):
        parser.add_argument(
            "zip_path",
            type=str,
            help="Path to the .patch.zip or update archive file."
        )
        parser.add_argument(
            "--user",
            type=str,
            default="cli_admin",
            help="Username attributing this update."
        )

    def handle(self, *args, **options):
        zip_path = options["zip_path"]
        applied_by = options["user"]

        if not os.path.exists(zip_path):
            self.stderr.write(self.style.ERROR(f"File not found: {zip_path}"))
            sys.exit(1)

        self.stdout.write(self.style.MIGRATE_HEADING(f"\n=== Deploying System Hot-Patch: {zip_path} ==="))

        try:
            patch_record, manifest = PatchEngine.apply_patch(zip_path, applied_by=applied_by)
        except Exception as e:
            self.stderr.write(self.style.ERROR(f"Update failed: {e}"))
            sys.exit(1)

        self.stdout.write(self.style.SUCCESS(f"\nSUCCESS: Hot-Patch '{patch_record.patch_id}' applied successfully!"))
        self.stdout.write(f"Name: {patch_record.name} (v{patch_record.version})")
        self.stdout.write(f"Files Modified/Added: {patch_record.file_count}")
        self.stdout.write(f"Shadow Snapshot Vault: {patch_record.backup_dir}")
        self.stdout.write(self.style.SUCCESS("Zero-downtime Passenger reload triggered."))
        self.stdout.write(self.style.NOTICE(f"\nTo undo this update at any time, run:"))
        self.stdout.write(f"  python manage.py rollback_update --id {patch_record.patch_id}\n")
