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
import SystemUpdatesHub from './components/system/SystemUpdatesHub';
import PersonaModal from './components/automation/PersonaModal';
import TaskDispatchModal from './components/automation/TaskDispatchModal';
import AutomationsHub from './components/automation/AutomationsHub';
import FleetMonitorHub from './components/automation/FleetMonitorHub';
import CalibrationHub from './components/automation/CalibrationHub';
import WorkflowBuilderHub from './components/automation/WorkflowBuilderHub';
import LaunchCampaignHub from './components/automation/LaunchCampaignHub';
import AddonsHub from './components/automation/AddonsHub';
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
  const [relaunchTask, setRelaunchTask] = useState(null);
  const [relaunchProfileIds, setRelaunchProfileIds] = useState([]);
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  const handleOpenDispatch = (task = null, profileIds = []) => {
    setRelaunchTask(task);
    setRelaunchProfileIds(profileIds || []);
    setShowDispatchModal(true);
  };
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
    <div className="flex min-h-screen bg-[#0A0D14] text-neutral-100 font-sans antialiased selection:bg-blue-600 selection:text-white relative">
      {/* Mobile Drawer Backdrop */}
      {isMobileMenuOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm lg:hidden transition-opacity"
          onClick={() => setIsMobileMenuOpen(false)}
        />
      )}

      {/* 1. Responsive Sidebar Navigation (Docked on desktop, Drawer on mobile) */}
      <Sidebar
        activeTab={activeTab}
        setActiveTab={(tab) => {
          setActiveTab(tab);
          setIsMobileMenuOpen(false);
        }}
        onOpenDispatch={() => {
          setActiveTab('LAUNCH');
          setIsMobileMenuOpen(false);
        }}
        profilesCount={profiles.length}
        runningJobsCount={runningJobsCount}
        activeModelName={settings.selected_ai_model}
        isAudioMuted={settings.force_global_mute}
        isMobileOpen={isMobileMenuOpen}
        onCloseMobile={() => setIsMobileMenuOpen(false)}
        onLogout={onLogout}
      />

      {/* 2. Main Content Workspace */}
      <div className="flex-1 flex flex-col min-w-0 overflow-x-hidden">
        {/* Top Header Bar */}
        <TopBar
          activeTab={activeTab}
          settings={settings}
          profilesCount={profiles.length}
          onRefresh={loadDashboardData}
          statusMsg={statusMsg}
          statusType={statusType}
          onOpenDispatch={() => setActiveTab('LAUNCH')}
          onToggleMobileMenu={() => setIsMobileMenuOpen((prev) => !prev)}
          onLogout={onLogout}
        />

        {/* Dynamic View Panels */}
        {activeTab === 'WORKFLOW_BUILDER' || activeTab === 'LAUNCH' ? (
          <main className="w-full flex-1 flex flex-col overflow-hidden">
            {activeTab === 'WORKFLOW_BUILDER' && <WorkflowBuilderHub onNotification={showNotification} />}
            {activeTab === 'LAUNCH' && (
              <LaunchCampaignHub
                onDispatched={() => {
                  setActiveTab('EXECUTION');
                  loadProfiles();
                  pollRunningJobs();
                }}
              />
            )}
          </main>
        ) : (
          <main className="p-3.5 sm:p-5 lg:p-8 max-w-7xl w-full mx-auto flex-1 min-w-0">
            {activeTab === 'ADDONS' && (
              <AddonsHub
                onNotification={showNotification}
                onNavigateToWorkflow={() => setActiveTab('WORKFLOW_BUILDER')}
              />
            )}

            {activeTab === 'AUTOMATIONS' && (
              <AutomationsHub onOpenDispatch={(task, pIds) => handleOpenDispatch(task, pIds)} />
            )}

          {activeTab === 'CALIBRATION' && (
            <CalibrationHub onNotification={showNotification} />
          )}

          {activeTab === 'FLEET' && (
            <FleetMonitorHub />
          )}

          {activeTab === 'EXECUTION' && (
            <ExecutionConsole onOpenDispatch={(task, pIds) => handleOpenDispatch(task, pIds)} />
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

          {activeTab === 'UPDATES' && (
            <SystemUpdatesHub showNotification={showNotification} />
          )}
        </main>
      )}
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
          initialTask={relaunchTask}
          initialProfileIds={relaunchProfileIds}
          onClose={() => {
            setShowDispatchModal(false);
            setRelaunchTask(null);
            setRelaunchProfileIds([]);
          }}
          onDispatched={() => {
            setActiveTab('EXECUTION');
            loadProfiles();
            pollRunningJobs();
            setRelaunchTask(null);
            setRelaunchProfileIds([]);
          }}
        />
      )}
    </div>
  );
}
