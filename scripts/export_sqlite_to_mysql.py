import sqlite3
import os
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
DB_PATH = ROOT_DIR / "backend" / "db.sqlite3"
OUT_SQL = ROOT_DIR / "dist" / "namecheap" / "migrate_to_mysql.sql"
OUT_SQL.parent.mkdir(parents=True, exist_ok=True)

# Tables containing user settings and application data to migrate
DATA_TABLES = [
    "auth_user",
    "authtoken_token",
    "devices_device",
    "devices_globalsetting",
    "devices_llmconfig",
    "devices_proxyconfiguration",
    "devices_profilefingerprint",
    "devices_browserstorage",
    "devices_savedprofile",
    "ai_assistant_aiproviderconfig",
    "ai_assistant_globalaisetting",
    "automation_aipromptconfig",
    "automation_assistantsession",
    "automation_assistantmessage",
    "automation_customworkflow",
    "automation_workflowaddon",
    "automation_niche",
    "automation_profilenicheaffiliation",
    "automation_profilepersona",
    "automation_platformcalibration",
    "automation_automationtask",
    "automation_taskexecutionqueue",
    "executions_execution",
    "executions_executionlease",
    "executions_executionevent",
    "system_updater_systempatch",
]

def escape_mysql_value(val):
    if val is None:
        return "NULL"
    elif isinstance(val, (int, float)):
        return str(val)
    elif isinstance(val, bytes):
        hex_str = val.hex()
        return f"X'{hex_str}'"
    else:
        # String escaping
        s = str(val)
        s = s.replace("\\", "\\\\")
        s = s.replace("'", "\\'")
        s = s.replace("\0", "\\0")
        s = s.replace("\n", "\\n")
        s = s.replace("\r", "\\r")
        return f"'{s}'"

def generate_mysql_dump():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    lines = []
    lines.append("-- =====================================================================")
    lines.append("-- TersooPilot MySQL Data Migration Script (PC SQLite -> Namecheap MySQL)")
    lines.append("-- Database: terswacw_terpilt")
    lines.append("-- =====================================================================\n")
    lines.append("SET NAMES utf8mb4;")
    lines.append("SET FOREIGN_KEY_CHECKS = 0;\n")

    total_rows = 0

    for table in DATA_TABLES:
        # Check if table exists in SQLite
        cur.execute(f"SELECT name FROM sqlite_master WHERE type='table' AND name=?", (table,))
        if not cur.fetchone():
            continue

        # Get column names
        cur.execute(f"PRAGMA table_info(\"{table}\")")
        cols = [row[1] for row in cur.fetchall()]
        cols_formatted = ", ".join([f"`{c}`" for c in cols])

        # Fetch all rows
        cur.execute(f"SELECT * FROM \"{table}\"")
        rows = cur.fetchall()
        if not rows:
            continue

        print(f"Exporting {len(rows)} rows from {table}...")
        total_rows += len(rows)

        lines.append(f"-- Data for {table} ({len(rows)} records)")
        # Insert in chunks of 50 to keep statements compact and avoid max_allowed_packet limits
        chunk_size = 50
        for i in range(0, len(rows), chunk_size):
            chunk = rows[i:i + chunk_size]
            val_strs = []
            for row in chunk:
                escaped_vals = [escape_mysql_value(v) for v in row]
                val_strs.append(f"({', '.join(escaped_vals)})")
            stmt = f"REPLACE INTO `{table}` ({cols_formatted}) VALUES\n  " + ",\n  ".join(val_strs) + ";"
            lines.append(stmt)
        lines.append("")

    lines.append("SET FOREIGN_KEY_CHECKS = 1;")
    lines.append("-- End of Migration Script\n")

    with open(OUT_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"\nMigration SQL generated at: {OUT_SQL}")
    print(f"Total records exported: {total_rows}")
    print(f"File size: {os.path.getsize(OUT_SQL) / 1024:.2f} KB")

if __name__ == "__main__":
    generate_mysql_dump()
