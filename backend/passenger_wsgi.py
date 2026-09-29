import os
import sys

# Add the project directory to the sys.path so modules can be imported
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

# Set the Django settings module
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "core.settings")

# Phusion Passenger looks for the 'application' callable
from django.core.wsgi import get_wsgi_application
application = get_wsgi_application()
