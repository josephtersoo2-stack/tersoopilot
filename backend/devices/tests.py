import json
from django.contrib.auth.models import User
from django.test import TestCase, override_settings
from rest_framework.test import APIClient
from automation.models import AutomationTask, TaskExecutionQueue, AIPromptConfig
from .models import SavedProfile


@override_settings(PASSWORD_HASHERS=["django.contrib.auth.hashers.MD5PasswordHasher"])
class AccessAndIntegrityTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user('owner', password='Strong-test-password-41')
        self.other = User.objects.create_user('other', password='Strong-test-password-42')
        self.staff = User.objects.create_user('staff', is_staff=True)
        self.profile = SavedProfile.objects.create(user=self.owner, name='Device', device_sync_id='local-device')
        self.client = APIClient()
        self.task = AutomationTask.objects.create(name='Test', category='CUSTOM')
        self.job = TaskExecutionQueue.objects.create(task=self.task, profile=self.profile,
            status='RUNNING', current_state_id='start', compiled_dag={'states': {
                'start': {'command': 'WAIT', 'transitions': {'SUCCESS': 'exit', 'FAILURE': 'exit'}}}})

    def test_anonymous_endpoints_are_denied(self):
        for url in ('profiles/', 'settings/global/', 'automation/ghostpilot/', 'automation/tasks/',
                    'automation/assistant/', f'profiles/{self.profile.id}/cookies/export/'):
            with self.subTest(url=url):
                self.assertEqual(self.client.get('/api/' + url).status_code, 401)

    def test_profiles_and_cookies_are_owner_scoped(self):
        self.client.force_authenticate(self.other)
        self.assertEqual(self.client.get('/api/profiles/').data, [])
        for identifier in (self.profile.id, 'local-device'):
            url = f'/api/profiles/{identifier}/cookies/'
            self.assertEqual(self.client.get(url + 'export/').status_code, 404)
            self.assertEqual(self.client.post(url + 'import/', {'cookies': []}, format='json').status_code, 404)

    def test_profile_owner_cannot_be_reassigned(self):
        self.client.force_authenticate(self.owner)
        response = self.client.patch(f'/api/profiles/{self.profile.id}/', {'user': self.other.id}, format='json')
        self.assertEqual(response.status_code, 200)
        self.profile.refresh_from_db()
        self.assertEqual(self.profile.user, self.owner)

    def test_unassigned_profiles_hidden_from_members(self):
        SavedProfile.objects.create(name='Legacy')
        self.client.force_authenticate(self.other)
        self.assertEqual(self.client.get('/api/profiles/').data, [])

    def test_settings_and_assistant_require_staff(self):
        self.client.force_authenticate(self.owner)
        self.assertEqual(self.client.get('/api/settings/global/').status_code, 200)
        self.assertEqual(self.client.patch('/api/settings/global/', {}, format='json').status_code, 403)
        self.assertEqual(self.client.get('/api/automation/assistant/').status_code, 403)

    def test_other_users_cannot_claim_or_mutate_jobs(self):
        self.job.status = 'PENDING'
        self.job.save()
        self.client.force_authenticate(self.other)
        response = self.client.get(f'/api/automation/ghostpilot/poll/{self.profile.id}/')
        self.assertFalse(response.data['work_available'])
        self.assertEqual(self.client.post(f'/api/automation/ghostpilot/{self.job.id}/abort/').status_code, 404)
        self.assertEqual(self.client.get(f'/api/automation/ghostpilot/{self.job.id}/stream/').status_code, 404)

    def test_failure_is_not_success_and_terminal_job_cannot_advance(self):
        self.client.force_authenticate(self.owner)
        url = f'/api/automation/ghostpilot/{self.job.id}/transition/'
        self.assertEqual(self.client.post(url, {'outcome': 'FAILURE'}, format='json').status_code, 200)
        self.job.refresh_from_db()
        self.assertEqual(self.job.status, 'FAILED')
        self.assertEqual(self.client.post(url, {'outcome': 'SUCCESS'}, format='json').status_code, 409)

    def test_invalid_transition_payload_is_rejected(self):
        self.client.force_authenticate(self.owner)
        url = f'/api/automation/ghostpilot/{self.job.id}/transition/'
        for payload in ({'outcome': 'UNKNOWN'}, {'context_update': []}):
            self.assertEqual(self.client.post(url, payload, format='json').status_code, 400)

    def test_execution_crud_cannot_bypass_state_machine(self):
        self.client.force_authenticate(self.owner)
        self.assertEqual(self.client.patch(f'/api/automation/ghostpilot/{self.job.id}/', {'status': 'SUCCESS'}, format='json').status_code, 405)

    def test_autosave_never_falls_back_to_unrelated_profile(self):
        self.client.force_authenticate(self.owner)
        response = self.client.post('/api/sync/auto-save/', {'device_sync_id': 'missing', 'name': 'Device', 'tabs_data': ['bad']}, format='json')
        self.assertEqual(response.status_code, 404)
        self.profile.refresh_from_db()
        self.assertEqual(self.profile.tabs_data, '[]')

    def test_sync_list_payload_and_cookie_count(self):
        self.client.force_authenticate(self.owner)
        cookie = {'name': 'a', 'value': 'b', 'domain': 'example.com'}
        response = self.client.post('/api/sync/push/', [{'device_sync_id': 'local-device', 'cookies_data': [cookie], 'cookie_count': 999}], format='json')
        self.assertEqual(response.status_code, 200)
        self.profile.refresh_from_db()
        self.assertEqual(self.profile.cookie_count, 1)
        self.assertEqual(json.loads(self.profile.cookies_data), [cookie])

    def test_invalid_sync_batch_rolls_back(self):
        self.client.force_authenticate(self.owner)
        response = self.client.post('/api/sync/push/', [
            {'device_sync_id': 'local-device', 'name': 'Changed'},
            {'device_sync_id': 'other-device', 'ram_gb': 'invalid'},
        ], format='json')
        self.assertEqual(response.status_code, 400)
        self.profile.refresh_from_db()
        self.assertEqual(self.profile.name, 'Device')

    def test_same_name_does_not_merge_devices(self):
        self.client.force_authenticate(self.owner)
        response = self.client.post('/api/sync/push/', [{'device_sync_id': 'second', 'name': 'Device'}], format='json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(SavedProfile.objects.filter(user=self.owner).count(), 2)

    def test_missing_config_activation_preserves_current(self):
        self.client.force_authenticate(self.staff)
        config = AIPromptConfig.objects.create(is_active=True)
        response = self.client.post('/api/automation/ai-config/set-active/', {'config_id': '00000000-0000-0000-0000-000000000001'}, format='json')
        self.assertEqual(response.status_code, 404)
        config.refresh_from_db()
        self.assertTrue(config.is_active)

    def test_weak_password_and_generic_login_errors(self):
        response = self.client.post('/api/auth/register/', {'username': 'new', 'password': '1234'}, format='json')
        self.assertEqual(response.status_code, 400)
        errors = [self.client.post('/api/auth/login/', {'username': name, 'password': 'wrong'}, format='json').data
                  for name in ('owner', 'missing')]
        self.assertEqual(errors[0], errors[1])

    def test_password_whitespace_is_preserved(self):
        password = '  Distinct-password-723!  '
        response = self.client.post('/api/auth/register/', {'username': 'spaces', 'password': password}, format='json')
        self.assertEqual(response.status_code, 201)
        self.assertTrue(User.objects.get(username='spaces').check_password(password))

    def test_duplicate_transition_is_idempotent(self):
        self.client.force_authenticate(self.owner)
        url = f'/api/automation/ghostpilot/{self.job.id}/transition/'
        payload = {'outcome': 'SUCCESS', 'transition_id': 'stable-request-id'}
        self.assertEqual(self.client.post(url, payload, format='json').status_code, 200)
        self.assertEqual(self.client.post(url, payload, format='json').status_code, 200)
        self.job.refresh_from_db()
        self.assertEqual(len(self.job.logs), 1)

    def test_assistant_write_mode_is_enforced_on_raw_callable(self):
        from automation.assistant_tools import tool_abort_job
        with override_settings(ASSISTANT_ALLOW_WRITES=False):
            result = tool_abort_job(job_id=str(self.job.id))
        self.assertIn('error', result)
        self.job.refresh_from_db()
        self.assertEqual(self.job.status, 'RUNNING')
