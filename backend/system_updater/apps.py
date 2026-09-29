from django.apps import AppConfig


class SystemUpdaterConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "system_updater"
    verbose_name = "System Hot-Patches & Updates"
