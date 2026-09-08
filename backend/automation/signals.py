import random
from django.db.models.signals import post_save
from django.dispatch import receiver
from devices.models import SavedProfile
from .models import ProfilePersona

@receiver(post_save, sender=SavedProfile)
def initialize_persona_for_profile(sender, instance, created, **kwargs):
    if created:
        ProfilePersona.objects.create(
            profile=instance,
            patience_index=round(random.uniform(0.4, 0.85), 2),
            engagement_rate=round(random.uniform(0.08, 0.25), 2),
            typing_wpm=random.randint(55, 85),
            typo_probability=round(random.uniform(0.02, 0.05), 3),
            trust_score=10
        )
