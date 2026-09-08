import React from 'react';
import { 
  RefreshCw, 
  AlertTriangle, 
  CheckCircle2, 
  Sliders, 
  Tv, 
  Play
} from 'lucide-react';

const TAB_TITLES = {
  EXECUTION: 'Live Execution & Telemetry Stream',
  PROFILES: 'Profiles & Behavioral Personas',
  NICHES: 'Target Audience & Niches Hub',
  AI_CONFIG: 'AI Model & System Prompt Studio',
  HARDWARE: 'Hardware Blueprint & Spec Engine',
  SETTINGS: 'Runtime Constraints & Concurrency'
};

const TAB_BREADCRUMBS = {
  EXECUTION: ['Operations', 'Live Execution Console'],
  PROFILES: ['Fleet', 'Profiles & Personas'],
  NICHES: ['Audience', 'Target Niches'],
  AI_CONFIG: ['Intelligence', 'Prompt Studio'],
  HARDWARE: ['Hardware', 'Device Blueprints'],
  SETTINGS: ['System', 'Runtime Constraints']
};

export default function TopBar({
  activeTab,
  settings,
  profilesCount = 0,
  onRefresh,
  statusMsg,
  statusType,
  onOpenDispatch
}) {
  const breadcrumb = TAB_BREADCRUMBS[activeTab] || ['GhostPilot', activeTab];
  const title = TAB_TITLES[activeTab] || activeTab;

  return (
    <header className="sticky top-0 z-20 backdrop-blur-md bg-[#0A0D14]/90 border-b border-[#1E2638] px-8 py-3.5 flex items-center justify-between">
      {/* Left Breadcrumbs & View Title */}
      <div>
        <div className="flex items-center gap-1.5 text-[11px] text-neutral-400 font-medium">
          <span>GhostPilot</span>
          <span className="text-neutral-600">/</span>
          <span>{breadcrumb[0]}</span>
          <span className="text-neutral-600">/</span>
          <span className="text-blue-400 font-semibold">{breadcrumb[1]}</span>
        </div>
        <h1 className="text-lg font-bold text-white tracking-tight mt-0.5">{title}</h1>
      </div>

      {/* Center / Status Toast Banner */}
      <div className="flex items-center gap-3">
        {statusMsg && (
          <div className={`flex items-center gap-1.5 text-xs px-3.5 py-1.5 rounded-xl border animate-fade-in ${
            statusType === 'error' 
              ? 'bg-rose-950/70 border-rose-800 text-rose-300' 
              : 'bg-emerald-950/70 border-emerald-800 text-emerald-300'
          }`}>
            {statusType === 'error' ? <AlertTriangle className="w-3.5 h-3.5" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
            <span>{statusMsg}</span>
          </div>
        )}

        {/* Live Metrics Pills */}
        <div className="hidden lg:flex items-center gap-2 text-xs">
          <div className="flex items-center gap-1.5 px-3 py-1.5 bg-[#131722] border border-[#1E2638] rounded-xl text-neutral-300">
            <Sliders className="w-3.5 h-3.5 text-blue-400" />
            <span className="text-neutral-400">Concurrency:</span>
            <span className="font-semibold text-white font-mono">{settings.max_active_profiles || 5}/10</span>
          </div>

          <div className="flex items-center gap-1.5 px-3 py-1.5 bg-[#131722] border border-[#1E2638] rounded-xl text-neutral-300">
            <Tv className="w-3.5 h-3.5 text-purple-400" />
            <span className="text-neutral-400">Video:</span>
            <span className="font-semibold text-white font-mono">{settings.default_video_resolution || '240p'}</span>
          </div>
        </div>

        {/* Action Controls */}
        <button
          onClick={onRefresh}
          className="flex items-center gap-1.5 bg-[#131722] hover:bg-[#1A2030] text-neutral-300 hover:text-white px-3 py-1.5 rounded-xl border border-[#1E2638] text-xs font-medium transition cursor-pointer"
          title="Refresh telemetry"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span className="hidden sm:inline">Refresh</span>
        </button>

        <button
          onClick={onOpenDispatch}
          className="flex items-center gap-1.5 bg-blue-600 hover:bg-blue-500 text-white px-3.5 py-1.5 rounded-xl text-xs font-semibold shadow-md shadow-blue-600/20 transition cursor-pointer"
        >
          <Play className="w-3 h-3 fill-white" />
          <span className="hidden sm:inline">Launch Campaign</span>
        </button>
      </div>
    </header>
  );
}
