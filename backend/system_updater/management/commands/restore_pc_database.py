import os
from pathlib import Path
from django.core.management.base import BaseCommand
from django.core.management import call_command
from django.db import connection

class Command(BaseCommand):
    help = "Restores data from PC database dump (data_dump_clean.json) into Namecheap MySQL"

    def add_arguments(self, parser):
        parser.add_argument(
            "--file",
            type=str,
            default=None,
            help="Path to the json dump file to restore"
        )

    def handle(self, *args, **options):
        base_dir = Path(__file__).resolve().parent.parent.parent.parent
        dump_file = options["file"]
        if not dump_file:
            # Check default candidate paths
            candidates = [
                base_dir / "data_dump_clean.json",
                base_dir / "data_dump.json",
                base_dir / "dist" / "namecheap" / "data_dump_clean.json"
            ]
            for c in candidates:
                if c.exists():
                    dump_file = str(c)
                    break

        if not dump_file or not os.path.exists(dump_file):
            self.stderr.write(self.style.ERROR(
                f"Dump file not found! Checked locations. Please provide --file <path_to_data_dump_clean.json>"
            ))
            return

        self.stdout.write(self.style.MIGRATE_HEADING(f"==> Restoring PC database from: {dump_file}"))

        with connection.cursor() as cursor:
            # Disable foreign key checks for clean bulk import on MySQL
            db_vendor = connection.vendor
            if db_vendor == "mysql":
                cursor.execute("SET FOREIGN_KEY_CHECKS = 0;")
                self.stdout.write("  [+] Disabled MySQL foreign key checks.")

            try:
                call_command("loaddata", dump_file)
                self.stdout.write(self.style.SUCCESS("  [SUCCESS] All models restored successfully into database!"))
            except Exception as e:
                self.stderr.write(self.style.ERROR(f"  [ERROR] Failed to load data: {e}"))
                raise
            finally:
                if db_vendor == "mysql":
                    cursor.execute("SET FOREIGN_KEY_CHECKS = 1;")
                    self.stdout.write("  [+] Re-enabled MySQL foreign key checks.")

        self.stdout.write(self.style.SUCCESS("==> Database migration complete! All profiles, settings, and users are active."))
