# Namecheap Production Deployment & WordPress-Style Update Guide

This guide walks you step-by-step through deploying the **TersoPilot Django Backend** (with MySQL) and **React Admin Panel** to **Namecheap cPanel (CloudLinux Python App / Passenger)**, and shows you how to use the advanced **WordPress-Style Hot-Patch Update System** with **one-command terminal rollback**.

---

## Architecture Overview on Namecheap

```
Namecheap cPanel Server
├── /home/youruser/public_html/                <-- React Admin Frontend (Built SPA + .htaccess)
│   ├── index.html
│   ├── assets/
│   └── .htaccess                             <-- Client-side routing & caching
│
├── /home/youruser/multibrowser_backend/       <-- Python 3.11/3.12 App Root
│   ├── passenger_wsgi.py                     <-- Passenger entrypoint
│   ├── manage.py
│   ├── .env                                  <-- MySQL & Security credentials
│   ├── core/
│   ├── system_updater/                       <-- Hot-Patch & Rollback engine
│   ├── storage/updates/rollback/<patch_id>/  <-- Shadow Snapshot Vault for instant undo
│   └── tmp/restart.txt                       <-- Passenger zero-downtime reload trigger
│
└── MySQL Database (Localhost:3306)            <-- cPanel MySQL Database
```

---

## Part 1: Automated Packaging

To generate ready-to-upload zip archives on your local machine, run:

```bash
python scripts/package_for_namecheap.py
```

This will automatically create:
1. `dist/namecheap/namecheap_backend.zip` (Clean MySQL-ready Django backend)
2. `dist/namecheap/namecheap_public_html.zip` (Built React frontend + `.htaccess`)
3. `dist/namecheap/namecheap_complete_bundle.zip` (All-in-one bundle)

---

## Part 2: Step-by-Step Namecheap cPanel Installation

### Step 1: Create Your MySQL Database in cPanel
1. Log into your **Namecheap cPanel**.
2. Navigate to **Databases** &rarr; **MySQL Databases**.
3. Under **Create New Database**, enter a name (e.g., `terso_db`) and click **Create Database**.
   *(Note full name: e.g. `cpaneluser_terso_db`)*.
4. Under **Add New User**, enter a username (e.g. `terso_user`), generate a strong password, and click **Create User**.
5. Under **Add User To Database**, select the user and database, click **Add**, check **ALL PRIVILEGES**, and click **Make Changes**.

---

### Step 2: Setup Python Application in cPanel
1. In cPanel, find **Software** &rarr; **Setup Python App**.
2. Click **Create Application**.
3. Fill in the parameters:
   - **Python version**: Select `3.11` or `3.12`.
   - **Application root**: `multibrowser_backend` (or your preferred directory name).
   - **Application URL**: `api.yourdomain.com` (or `yourdomain.com/api` or root).
   - **Application startup file**: `passenger_wsgi.py`.
   - **Application Entry point**: `application`.
4. Click **Create** at top right.
5. Note the **virtual environment activation command** displayed at the top of the page (e.g., `source /home/cpaneluser/virtualenv/multibrowser_backend/3.11/bin/activate`).

---

### Step 3: Upload & Configure the Backend
1. Open cPanel **File Manager**.
2. Go to the application root directory created in Step 2: `/home/cpaneluser/multibrowser_backend`.
3. Upload `namecheap_backend.zip` and click **Extract**.
4. Inside `multibrowser_backend`:
   - Rename `.env.namecheap.example` to `.env` (or create a new `.env` file).
   - Fill in your MySQL credentials and domain:
     ```env
     SECRET_KEY=enter-a-strong-random-50-character-secret-key
     DEBUG=False
     ALLOWED_HOSTS=yourdomain.com,www.yourdomain.com,api.yourdomain.com,127.0.0.1,localhost

     DB_ENGINE=mysql
     MYSQL_DATABASE=cpaneluser_terso_db
     MYSQL_USER=cpaneluser_terso_user
     MYSQL_PASSWORD=YourDatabasePassword
     MYSQL_HOST=localhost
     MYSQL_PORT=3306

     CORS_ALLOWED_ORIGINS=https://yourdomain.com,https://www.yourdomain.com
     TOKEN_EXPIRY_HOURS=72
     ```
5. Open cPanel **Terminal** (or connect via SSH):
   ```bash
   # 1. Activate the Python virtual environment
   source /home/cpaneluser/virtualenv/multibrowser_backend/3.11/bin/activate

   # 2. Enter backend directory
   cd /home/cpaneluser/multibrowser_backend

   # 3. Install requirements (including PyMySQL and cryptography)
   pip install -r requirements.txt

   # 4. Run MySQL database migrations
   python manage.py migrate

   # 5. RESTORE ALL PC SETTINGS, PROFILES & USERS INTO MYSQL (Instant Migration)
   python manage.py restore_pc_database

   # 6. Collect static files
   python manage.py collectstatic --noinput
   ```

---

### Step 3b: Alternative Database Migration (via phpMyAdmin)
If you prefer migrating via the cPanel graphical interface:
1. In cPanel, open **Databases** &rarr; **phpMyAdmin**.
2. Click your database (`terswacw_terpilt`) in the left sidebar.
3. Click the **Import** tab at the top.
4. Click **Choose File** and select `dist/namecheap/migrate_to_mysql.sql`.
5. Scroll down and click **Import / Go**.
6. All 1,296 records (profiles, fingerprints, proxies, users, tokens, AI models, settings) will be imported immediately!

---

### Step 4: Deploy the React Admin Frontend
1. In cPanel **File Manager**, navigate to your web root:
   - For your main domain: `/home/cpaneluser/public_html`
   - For a subdomain: `/home/cpaneluser/app.yourdomain.com`
2. Upload `namecheap_public_html.zip`.
3. Click **Extract**.
4. Confirm `index.html`, `assets/`, and `.htaccess` are directly inside the folder.
5. In your browser, open `https://yourdomain.com` &rarr; Your React Admin Panel will load immediately with fast Gzip caching and SPA routing.

---

## Part 3: WordPress-Style Update & Modification System

When you make a modification, bugfix, or add a feature in development, you never have to re-upload the entire codebase. You deploy it just like a WordPress plugin!

### 1. Creating a Hot-Patch Archive
On your local machine, run the patch creator tool:

```bash
# Option A: Package specific modified files
python scripts/create_patch.py --name "Cookie Sync Fix" --version "1.1.0" --files backend/sync/CookieEngine.kt backend/devices/views.py

# Option B: Automatically package all modified git files in backend/
python scripts/create_patch.py --name "Real-Time Telemetry Update" --version "1.2.0" --git

# Option C: Include database migrations
python scripts/create_patch.py --name "New Schema Model" --version "2.0.0" --git --migrations
```

The script produces a `.patch.zip` in `dist/patches/` with a verified `patch.json` manifest.

### 2. Uploading the Update (No Terminal Needed)
You can upload the patch from either:
- **React Admin Panel**:
  Go to **Hardware & System** &rarr; **System Updates & Patches** &rarr; Drag and drop the `.patch.zip` file and click **Deploy Update Now**.
- **Django Admin**:
  Go to `https://yourdomain.com/admin/` &rarr; **System Hot-Patches** &rarr; Click **Upload Update (.zip)** &rarr; Choose file &rarr; Deploy.

#### What Happens Under the Hood:
1. **Pre-flight Security**: Checks for zip-slip directory traversal and prevents tampering with protected files (`.env`, database files).
2. **Atomic Shadow Snapshot**: Before modifying anything, copies every file that will be overwritten into a versioned shadow snapshot vault: `storage/updates/rollback/<patch_id>/backup/`.
3. **Atomic File Deployment**: Copies the new/updated files into place.
4. **Post-Hooks**: Runs `python manage.py migrate` automatically if migrations are in the package.
5. **Zero-Downtime Reload**: Automatically touches `tmp/restart.txt`, triggering Phusion Passenger to reload your Python application instantly without restarting the entire server!

---

## Part 4: One-Command Emergency Terminal Rollback

If an uploaded update introduces a bug or unexpected behavior, you can revert it immediately from the terminal with **zero collateral damage** to any other files.

Open your cPanel Terminal or SSH:

```bash
cd /home/cpaneluser/multibrowser_backend
source /home/cpaneluser/virtualenv/multibrowser_backend/3.11/bin/activate
```

### 1. Revert the Latest Applied Update:
```bash
python manage.py rollback_update
```

### 2. Preview What Will Be Reverted (Dry-Run):
```bash
python manage.py rollback_update --dry-run
```

### 3. Revert a Specific Patch ID:
```bash
python manage.py rollback_update --id patch_cookie_sync_fix_20260927_103000
```

### 4. List All Applied and Rolled Back Updates:
```bash
python manage.py list_updates
```

#### Why This Is Safe:
- Files marked `RESTORE` are restored to their exact original pre-patch state from the shadow snapshot vault.
- Files marked `DELETE` (files newly introduced by the patch) are cleanly deleted along with any newly created empty subdirectories.
- **Untouched files are completely unaffected.**
- `tmp/restart.txt` is touched to trigger an instant Passenger application reload.
- The update can also be undone from the React Dashboard by clicking **Undo Update**.
