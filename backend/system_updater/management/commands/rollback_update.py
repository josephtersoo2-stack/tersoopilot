import sys
from django.core.management.base import BaseCommand
from system_updater.engine import PatchEngine


class Command(BaseCommand):
    help = "Instantly rolls back an applied system hot-patch/update without affecting any other file."

    def add_arguments(self, parser):
        parser.add_argument(
            "--id",
            type=str,
            help="Specific Patch ID to roll back (defaults to the latest applied active patch)."
        )
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Simulate the rollback and show files that will be restored or deleted without making changes."
        )
        parser.add_argument(
            "--list",
            action="store_true",
            help="List all applied and rolled-back updates before deciding."
        )

    def handle(self, *args, **options):
        if options["list"]:
            patches = PatchEngine.list_patches()
            if not patches:
                self.stdout.write(self.style.WARNING("No system patches recorded in the ledger or database."))
                return

            self.stdout.write(self.style.MIGRATE_HEADING("\n=== TersoPilot System Update History ==="))
            for p in patches:
                status_color = self.style.SUCCESS if p.get("status") == "ACTIVE" else self.style.NOTICE
                self.stdout.write(
                    f" - {p.get('patch_id')}: {p.get('name')} (v{p.get('version')}) "
                    f"[{status_color(p.get('status'))}] Applied: {p.get('applied_at')}"
                )
            self.stdout.write("")
            return

        patch_id = options.get("id")
        dry_run = options.get("dry_run", False)

        self.stdout.write(self.style.MIGRATE_HEADING("\n=== TersoPilot Hot-Patch Rollback Engine ==="))
        if dry_run:
            self.stdout.write(self.style.WARNING(">>> RUNNING IN DRY-RUN MODE (No filesystem changes will be made) <<<\n"))

        try:
            result = PatchEngine.rollback_patch(patch_id=patch_id, dry_run=dry_run)
        except Exception as e:
            self.stderr.write(self.style.ERROR(f"Rollback failed: {e}"))
            sys.exit(1)

        target_id = result["patch_id"]
        restored = result["restored_files"]
        deleted = result["deleted_files"]
        errors = result["errors"]

        self.stdout.write(f"Target Patch: {self.style.SUCCESS(target_id)}")
        self.stdout.write(f"Files to restore (reverted to original shadow snapshot): {len(restored)}")
        for f in restored:
            self.stdout.write(f"  [RESTORE] {f}")

        self.stdout.write(f"Files to delete (newly created by patch): {len(deleted)}")
        for f in deleted:
            self.stdout.write(f"  [DELETE]  {f}")

        if errors:
            self.stderr.write(self.style.WARNING("\nWarnings encountered during operation:"))
            for err in errors:
                self.stderr.write(f"  - {err}")

        if dry_run:
            self.stdout.write(self.style.SUCCESS("\n[DRY RUN COMPLETE] To perform the actual rollback, run:"))
            self.stdout.write(f"  python manage.py rollback_update --id {target_id}\n")
        else:
            self.stdout.write(self.style.SUCCESS(f"\nSUCCESS: Patch '{target_id}' has been cleanly rolled back."))
            self.stdout.write(self.style.SUCCESS("All original files have been restored and newly added files removed."))
            self.stdout.write(self.style.SUCCESS("Passenger application restart triggered (tmp/restart.txt touched).\n"))
