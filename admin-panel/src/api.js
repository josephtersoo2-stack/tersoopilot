import axios from 'axios';

const API = axios.create({
  baseURL: (import.meta.env.VITE_API_URL || '/api/').replace(/\/?$/, '/'),
  timeout: 30000,
});

export const fetchGlobalSettings = () => API.get('settings/global/');
export const updateGlobalSettings = (data) => API.patch('settings/global/', data);
export const fetchProfiles = () => API.get('profiles/');
export const deleteProfile = (profileId) => API.delete(`profiles/${profileId}/`);
export const bulkDeleteProfiles = (profileIds) => API.post('profiles/bulk-delete/', { profile_ids: profileIds });
export const generateDeviceSpecs = (query, provider = null, model = null) => 
  API.post('devices/generate/', { query, provider, model });
export const fetchAvailableModels = (provider) => 
  API.get(`devices/models/?provider=${encodeURIComponent(provider)}`);
export const importProfileCookies = (profileId, cookies) => 
  API.post(`profiles/${profileId}/cookies/import/`, { cookies });

// Automation V2 Rules & Runs
export const fetchAutomations = () => API.get('automation/automations/');
export const fetchAutomationDetail = (id) => API.get(`automation/automations/${id}/`);
export const createAutomation = (data) => API.post('automation/automations/', data);
export const updateAutomation = (id, data) => API.patch(`automation/automations/${id}/`, data);
export const deleteAutomation = (id) => API.delete(`automation/automations/${id}/`);
export const runAutomationNow = (id) => API.post(`automation/automations/${id}/run-now/`);
export const pauseAutomation = (id) => API.post(`automation/automations/${id}/pause/`);
export const resumeAutomation = (id) => API.post(`automation/automations/${id}/resume/`);

export const fetchAutomationRuns = () => API.get('automation/runs/');
export const fetchAutomationRunDetail = (id) => API.get(`automation/runs/${id}/`);
export const fetchRunExecutions = (runId) => API.get(`automation/runs/${runId}/executions/`);
export const cancelAutomationRun = (id) => API.post(`automation/runs/${id}/cancel/`);

// Fleet & Devices
export const fetchFleetDevices = () => API.get('devices/registry/');
export const fetchAutomationFleet = () => API.get('automation/fleet/');
export const disableFleetDevice = (id) => API.post(`devices/registry/${id}/disable/`);
export const enableFleetDevice = (id) => API.post(`devices/registry/${id}/enable/`);
export const abortExecution = (execId, reason = 'Aborted via Fleet Monitor') => 
  API.post(`automation/ghostpilot/${execId}/abort/`, { reason });

export const fetchAutomationTasks = () => API.get('automation/tasks/');
export const fetchAutomationTaskDetail = (id) => API.get(`automation/tasks/${id}/`);
export const updateAutomationTask = (id, data) => API.patch(`automation/tasks/${id}/`, data);
export const dispatchAutomationTask = (id, profileIds) => API.post(`automation/tasks/${id}/dispatch/`, { profile_ids: profileIds });
export const fetchNiches = () => API.get('automation/niches/');

// Calibration API
export const fetchCalibration = (platform = 'YOUTUBE') => API.get(`automation/calibration/?platform=${platform}`);
export const updateCalibration = (data) => API.post('automation/calibration/', data);
export const calibrateWithVLM = (data) => API.post('automation/calibration/calibrate-with-llm/', data);
export const captureDeviceScreen = (data = {}) => API.post('automation/calibration/capture-device/', data);
export const downloadCalibrationJson = (platform = 'YOUTUBE') => API.get(`automation/calibration/download-json/?platform=${platform}`);
export const flushCalibration = (platform = 'YOUTUBE') => API.post('automation/calibration/flush/', { platform });
export const importCalibrationJson = (data, platform = 'YOUTUBE') => {
  if (data instanceof FormData) {
    return API.post('automation/calibration/import-json/', data, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  }
  return API.post('automation/calibration/import-json/', { data, platform });
};

// Standalone Spatial Anchor & Testing APIs
export const saveSingleAnchor = (data) => API.post('automation/calibration/anchor/', data);
export const deleteSingleAnchor = (anchorId, platform = 'YOUTUBE') => API.delete(`automation/calibration/anchor/${anchorId}/?platform=${platform}`);
export const testAnchorTap = (data) => API.post('automation/calibration/test-tap/', data);
export const pullCalibrationFromDevice = (data = {}) => API.post('automation/calibration/pull-from-device/', data);
export const pushCalibrationToDevice = (data = {}) => API.post('automation/calibration/push-to-device/', data);

// Custom Visual Workflows API
export const fetchCustomWorkflows = () => API.get('automation/custom-workflows/');
export const fetchCustomWorkflowDetail = (id) => API.get(`automation/custom-workflows/${id}/`);
export const saveCustomWorkflow = (data) => {
  if (data.id) {
    return API.put(`automation/custom-workflows/${data.id}/`, data);
  }
  return API.post('automation/custom-workflows/', data);
};
export const deleteCustomWorkflow = (id) => API.delete(`automation/custom-workflows/${id}/`);
export const dispatchCustomWorkflow = (id, profileIds, overrides = {}) => API.post(`automation/custom-workflows/${id}/dispatch/`, { profile_ids: profileIds, overrides });

// Workflow Addons & Extensions API
export const fetchAddons = () => API.get('automation/addons/');
export const fetchAddonDetail = (id) => API.get(`automation/addons/${id}/`);
export const uploadAddonZip = (formData) => API.post('automation/addons/upload_zip/', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
});
export const toggleAddonActive = (id) => API.post(`automation/addons/${id}/toggle_active/`);
export const uninstallAddon = (id) => API.delete(`automation/addons/${id}/uninstall/`);
export const exportAddonZipUrl = (id) => `${API.defaults.baseURL}automation/addons/${id}/export_zip/`;
export const fetchAddonDynamicSteps = () => API.get('automation/addons/steps/');
export const fetchAddonTemplates = () => API.get('automation/addons/templates/');
export const importAddonTemplate = (addonId, templateName) => API.post('automation/addons/import_template/', {
  addon_id: addonId,
  template_name: templateName
});

export const analyzeYouTubeVideo = (url, provider = null) => API.post('automation/youtube/analyze/', { url, provider });

// System Hot-Patches & Updates API
export const fetchSystemUpdates = () => API.get('system/updates/');
export const uploadSystemPatch = (formData) => API.post('system/updates/upload/', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
});
export const rollbackSystemPatch = (patchId = null, dryRun = false) => API.post('system/updates/rollback/', {
  patch_id: patchId,
  dry_run: dryRun
});

export default API;
export const getToken = () => sessionStorage.getItem('terso_token');
export function clearSession() {
  sessionStorage.removeItem('terso_token');
  window.dispatchEvent(new Event('auth:expired'));
}
API.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Token ${token}`;
  return config;
});
API.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) clearSession();
  return Promise.reject(error);
});
export async function downloadCookies(profileId) {
  const response = await API.get(`profiles/${profileId}/cookies/export/?download=true`, { responseType: 'blob' });
  const url = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = `cookies_${profileId}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
