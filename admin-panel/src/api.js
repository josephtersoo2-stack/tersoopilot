import axios from 'axios';

const API = axios.create({
  baseURL: (import.meta.env.VITE_API_URL || '/api/').replace(/\/?$/, '/'),
  timeout: 30000,
});

export const fetchGlobalSettings = () => API.get('settings/global/');
export const updateGlobalSettings = (data) => API.patch('settings/global/', data);
export const fetchProfiles = () => API.get('profiles/');
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

// Tasks & Niches helpers
export const fetchAutomationTasks = () => API.get('automation/tasks/');
export const fetchNiches = () => API.get('automation/niches/');

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
