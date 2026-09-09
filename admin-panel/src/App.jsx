import React, { useState, useEffect } from 'react';
import axios from './api';
import Sidebar from './components/layout/Sidebar';
import TopBar from './components/layout/TopBar';
import ExecutionConsole from './components/automation/ExecutionConsole';
import ProfilesHub from './components/automation/ProfilesHub';
import NichesHub from './components/automation/NichesHub';
import AIPromptsHub from './components/automation/AIPromptsHub';
import TersoAssistantHub from './components/automation/TersoAssistantHub';
import FloatingAssistant from './components/automation/FloatingAssistant';
import HardwareBlueprintHub from './components/hardware/HardwareBlueprintHub';
import RuntimeSettingsHub from './components/system/RuntimeSettingsHub';
import PersonaModal from './components/automation/PersonaModal';
import TaskDispatchModal from './components/automation/TaskDispatchModal';
import { fetchGlobalSettings } from './api';

export default function App({ onLogout }) {
  const [activeTab, setActiveTab] = useState('EXECUTION');
  const [profiles, setProfiles] = useState([]);
  const [settings, setSettings] = useState({
    max_active_profiles: 5,
    force_global_mute: true,
    default_video_resolution: '240p',
    selected_ai_provider: 'openrouter',
    selected_ai_model: 'deepseek/deepseek-chat',
    saved_gemini_model: 'gemini-2.5-flash',
    saved_openrouter_model: 'deepseek/deepseek-chat',
    ai_generation_prompt: '',
    target_search_sites: '',
  });
  const [selectedPersonaProfile, setSelectedPersonaProfile] = useState(null);
  const [selectedProfileIds, setSelectedProfileIds] = useState([]);
  const [isAssistantOpen, setIsAssistantOpen] = useState(false);
  const [showDispatchModal, setShowDispatchModal] = useState(false);
  const [statusMsg, setStatusMsg] = useState('');
  const [statusType, setStatusType] = useState('success');
  const [loading, setLoading] = useState(true);
  const [runningJobsCount, setRunningJobsCount] = useState(0);

  useEffect(() => {
    loadDashboardData();
    const interval = setInterval(pollRunningJobs, 5000);
    return () => clearInterval(interval);
  }, []);

  const pollRunningJobs = async () => {
    try {
      const res = await axios.get('automation/ghostpilot/');
      const running = res.data.filter((j) => j.status === 'RUNNING').length;
      setRunningJobsCount(running);
    } catch (e) {
      showNotification('Cannot refresh execution status. Check the backend connection.', 'error');
    }
  };

  const showNotification = (msg, type = 'success') => {
    setStatusMsg(msg);
    setStatusType(type);
    setTimeout(() => setStatusMsg(''), 4000);
  };

  const loadDashboardData = async () => {
    setLoading(true);
    try {
      const [settRes, profRes, ghostRes] = await Promise.all([
        fetchGlobalSettings(),
        axios.get('profiles/'),
        axios.get('automation/ghostpilot/')
      ]);

      if (settRes.data && Object.keys(settRes.data).length > 0) {
        setSettings((prev) => ({ ...prev, ...settRes.data }));
      }
      setProfiles(profRes.data || []);
      const running = (ghostRes.data || []).filter((j) => j.status === 'RUNNING').length;
      setRunningJobsCount(running);
    } catch (err) {
      console.error('Failed to load dashboard data:', err);
      showNotification('Failed to load initial fleet data', 'error');
    } finally {
      setLoading(false);
    }
  };

  const loadProfiles = async () => {
    try {
      const res = await axios.get('profiles/');
      setProfiles(res.data || []);
    } catch (err) {
      console.error('Failed to refresh profiles:', err);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-[#0A0D14] flex flex-col items-center justify-center text-neutral-300">
        <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-xs font-mono text-neutral-400 tracking-wide">Connecting to GhostPilot Fleet Control...</p>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen bg-[#0A0D14] text-neutral-100 font-sans antialiased selection:bg-blue-600 selection:text-white">
      {/* 1. Fixed Left Sidebar Navigation */}
      <Sidebar
        activeTab={activeTab}
        setActiveTab={setActiveTab}
        onOpenDispatch={() => setShowDispatchModal(true)}
        profilesCount={profiles.length}
        runningJobsCount={runningJobsCount}
        activeModelName={settings.selected_ai_model}
        isAudioMuted={settings.force_global_mute}
      />

      {/* 2. Main Content Workspace */}
      <div className="flex-1 flex flex-col min-w-0 overflow-x-hidden">
        <div className="flex justify-end px-8 pt-3"><button onClick={onLogout} className="text-sm text-neutral-300 hover:text-white">Sign out</button></div>
        {/* Top Header Bar */}
        <TopBar
          activeTab={activeTab}
          settings={settings}
          profilesCount={profiles.length}
          onRefresh={loadDashboardData}
          statusMsg={statusMsg}
          statusType={statusType}
          onOpenDispatch={() => setShowDispatchModal(true)}
        />

        {/* Dynamic View Panels */}
        <main className="p-8 max-w-7xl w-full mx-auto flex-1">
          {activeTab === 'EXECUTION' && (
            <ExecutionConsole onOpenDispatch={() => setShowDispatchModal(true)} />
          )}

          {activeTab === 'PROFILES' && (
            <ProfilesHub
              profiles={profiles}
              setProfiles={setProfiles}
              onSelectPersona={setSelectedPersonaProfile}
              onRefresh={loadProfiles}
              showNotification={showNotification}
              selectedProfileIds={selectedProfileIds}
              setSelectedProfileIds={setSelectedProfileIds}
              onOpenAssistant={() => setIsAssistantOpen(true)}
            />
          )}

          {activeTab === 'NICHES' && <NichesHub />}

          {activeTab === 'ASSISTANT' && <TersoAssistantHub />}

          {activeTab === 'AI_CONFIG' && (
            <AIPromptsHub
              onSaved={(newModel) => setSettings((prev) => ({ ...prev, selected_ai_model: newModel }))}
            />
          )}

          {activeTab === 'HARDWARE' && (
            <HardwareBlueprintHub
              settings={settings}
              setSettings={setSettings}
              profiles={profiles}
              setProfiles={setProfiles}
              showNotification={showNotification}
            />
          )}

          {activeTab === 'SETTINGS' && (
            <RuntimeSettingsHub
              settings={settings}
              setSettings={setSettings}
              profilesCount={profiles.length}
              showNotification={showNotification}
            />
          )}
        </main>
      </div>

      {/* Global Floating Context-Aware AI Copilot (Appears Everywhere) */}
      <FloatingAssistant
        activeTab={activeTab}
        profiles={profiles}
        selectedProfileIds={selectedProfileIds}
        setSelectedProfileIds={setSelectedProfileIds}
        runningJobsCount={runningJobsCount}
        settings={settings}
        isOpen={isAssistantOpen}
        setIsOpen={setIsAssistantOpen}
      />

      {/* Modals */}
      {selectedPersonaProfile && (
        <PersonaModal
          profile={selectedPersonaProfile}
          onClose={() => setSelectedPersonaProfile(null)}
          onUpdated={loadProfiles}
        />
      )}

      {showDispatchModal && (
        <TaskDispatchModal
          profiles={profiles}
          onClose={() => setShowDispatchModal(false)}
          onDispatched={() => {
            setActiveTab('EXECUTION');
            loadProfiles();
            pollRunningJobs();
          }}
        />
      )}
    </div>
  );
}
