from django.contrib import admin
from django.urls import path, include
from django.http import HttpResponse

def home_status_view(request):
    html = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>OctoMobile Backend Server</title>
        <style>
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                background-color: #0c0f17;
                color: #e2e8f0;
                display: flex;
                align-items: center;
                justify-content: center;
                min-height: 100vh;
                padding: 24px;
            }
            .card {
                background-color: #111625;
                border: 1px solid #1e293b;
                border-radius: 16px;
                max-width: 620px;
                width: 100%;
                padding: 32px;
                box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
            }
            .header {
                display: flex;
                align-items: center;
                gap: 12px;
                margin-bottom: 20px;
            }
            .pulse {
                width: 14px;
                height: 14px;
                background-color: #10b981;
                border-radius: 50%;
                box-shadow: 0 0 12px #10b981;
                display: inline-block;
            }
            h1 { font-size: 22px; font-weight: 700; color: #ffffff; }
            p { font-size: 14px; color: #94a3b8; line-height: 1.6; margin-bottom: 20px; }
            .badge {
                display: inline-block;
                background-color: #064e3b;
                color: #6ee7b7;
                padding: 3px 10px;
                border-radius: 9999px;
                font-size: 12px;
                font-weight: 600;
                margin-bottom: 24px;
            }
            .links-grid {
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 14px;
                margin-bottom: 24px;
            }
            .link-btn {
                display: block;
                padding: 14px 16px;
                background-color: #182234;
                border: 1px solid #283548;
                border-radius: 10px;
                color: #ffffff;
                text-decoration: none;
                font-weight: 600;
                font-size: 13px;
                transition: all 0.2s;
                text-align: center;
            }
            .link-btn:hover {
                background-color: #2563eb;
                border-color: #3b82f6;
            }
            .creds-box {
                background-color: #0f172a;
                border: 1px solid #1e293b;
                border-radius: 10px;
                padding: 16px;
                font-family: monospace;
                font-size: 12px;
                color: #cbd5e1;
            }
            .creds-box strong { color: #38bdf8; }
        </style>
    </head>
    <body>
        <div class="card">
            <div class="header">
                <span class="pulse"></span>
                <h1>OctoMobile Django Backend is RUNNING</h1>
            </div>
            <div class="badge">Status: Online &amp; Healthy on port 8000</div>
            <p>The Django backend REST API and admin services are actively listening and accepting connections from Android clients and web administrative consoles.</p>
            
            <div class="links-grid">
                <a class="link-btn" href="/admin/">Django Admin Panel &rarr;</a>
                <a class="link-btn" href="http://localhost:5173/" target="_blank">React Admin Panel (5173) &rarr;</a>
                <a class="link-btn" href="/api/settings/global/">API: Global Settings &rarr;</a>
                <a class="link-btn" href="/api/profiles/">API: Profiles List &rarr;</a>
            </div>

            <div class="creds-box">
                <div><strong>Admin Access:</strong></div>
                <div style="margin-top: 6px;">Use credentials configured via <code>python manage.py createsuperuser</code></div>
            </div>
        </div>
    </body>
    </html>
    """
    return HttpResponse(html)

urlpatterns = [
    path("", home_status_view, name="home_status"),
    path("admin/", admin.site.urls),
    path("api/executions/", include("executions.urls")),
    path("api/automation/", include("automation.urls")),
    path("api/ai/", include("ai_assistant.urls")),
    path("api/", include("devices.urls")),
]
