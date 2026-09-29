from django.core.management.base import BaseCommand
from system_updater.engine import PatchEngine


class Command(BaseCommand):
    help = "Lists all applied and rolled back system updates and hot-patches."

    def handle(self, *args, **options):
        patches = PatchEngine.list_patches()
        if not patches:
            self.stdout.write(self.style.WARNING("No system patches recorded."))
            return

        self.stdout.write(self.style.MIGRATE_HEADING("\n=== Installed System Updates & Hot-Patches ==="))
        for p in patches:
            is_active = p.get("status") == "ACTIVE"
            status_style = self.style.SUCCESS if is_active else self.style.NOTICE
            self.stdout.write(
                f"[{status_style(p.get('status'))}] {p.get('patch_id')}\n"
                f"   Title:   {p.get('name')} (v{p.get('version')})\n"
                f"   Files:   {p.get('file_count', 0)} files affected\n"
                f"   Applied: {p.get('applied_at')} by {p.get('applied_by', 'system')}\n"
            )
        self.stdout.write(self.style.NOTICE("Undo the latest update anytime with: python manage.py rollback_update\n"))
