import React from 'react';
import { 
  Activity, 
  Play, 
  Users, 
  Layers, 
  Sparkles, 
  Smartphone, 
  Sliders, 
  ExternalLink,
  ShieldCheck,
  Zap,
  MessageCircle,
} from 'lucide-react';

export default function Sidebar({
  activeTab,
  setActiveTab,
  onOpenDispatch,
  profilesCount = 0,
  runningJobsCount = 0,
  activeModelName = '',
  isAudioMuted = true
}) {
  const navSections = [
    {
      title: 'OPERATIONS & TELEMETRY',
      items: [
        {
          id: 'EXECUTION',
          label: 'Execution Console',
          icon: Activity,
          badge: runningJobsCount > 0 ? `${runningJobsCount} active` : null,
          badgeColor: 'bg-amber-500/20 text-amber-300 border-amber-500/30'
        },
      ]
    },
    {
      title: 'FLEET & AUDIENCE',
      items: [
        {
          id: 'PROFILES',
          label: 'Profiles & Personas',
          icon: Users,
          badge: profilesCount > 0 ? `${profilesCount}` : null,
          badgeColor: 'bg-blue-500/20 text-blue-300 border-blue-500/30'
        },
        {
          id: 'NICHES',
          label: 'Target Niches Hub',
          icon: Layers,
        }
      ]
    },
    {
      title: 'INTELLIGENCE & AI',
      items: [
        {
          id: 'AI_CONFIG',
          label: 'AI Model & Prompt Studio',
          icon: Sparkles,
          subtitle: activeModelName ? activeModelName.split('/')[1] || activeModelName : null
        },
        {
          id: 'ASSISTANT',
          label: 'TersoAssistant Copilot',
          icon: MessageCircle,
        }
      ]
    },
    {
      title: 'HARDWARE & SYSTEM',
      items: [
        {
          id: 'HARDWARE',
          label: 'Hardware & Spec Engine',
          icon: Smartphone,
        },
        {
          id: 'SETTINGS',
          label: 'Runtime Constraints',
          icon: Sliders,
        }
      ]
    }
  ];

  return (
    <aside className="w-64 bg-[#0D111A] border-r border-[#1E2638] flex flex-col justify-between shrink-0 select-none min-h-screen">
      <div>
        {/* Brand Header */}
        <div className="p-5 border-b border-[#1E2638] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-blue-600 via-indigo-600 to-cyan-500 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <Zap className="w-5 h-5 text-white fill-white" />
            </div>
            <div>
              <div className="flex items-center gap-1.5">
                <span className="font-bold text-sm tracking-tight text-white">GhostPilot</span>
                <span className="text-[9px] font-mono bg-blue-500/10 text-blue-400 border border-blue-500/20 px-1.5 py-0.2 rounded font-semibold">
                  v3.0 DAG
                </span>
              </div>
              <p className="text-[10px] text-neutral-400">Fleet Control Center</p>
            </div>
          </div>
        </div>

        {/* Primary Action Button */}
        <div className="p-4 pb-2">
          <button
            onClick={onOpenDispatch}
            className="w-full bg-gradient-to-r from-blue-600 via-blue-500 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white text-xs font-semibold py-2.5 px-3.5 rounded-xl shadow-lg shadow-blue-600/25 flex items-center justify-center gap-2 transition cursor-pointer active:scale-[0.98]"
          >
            <Play className="w-3.5 h-3.5 fill-white" />
            <span>Launch Campaign</span>
          </button>
        </div>

        {/* Navigation Categories */}
        <nav className="p-3 space-y-5">
          {navSections.map((section, idx) => (
            <div key={idx}>
              <div className="text-[10px] font-semibold text-neutral-400 tracking-wider px-3 mb-1.5">
                {section.title}
              </div>
              <div className="space-y-0.5">
                {section.items.map((item) => {
                  const Icon = item.icon;
                  const isActive = activeTab === item.id;
                  return (
                    <button
                      key={item.id}
                      onClick={() => setActiveTab(item.id)}
                      className={`w-full flex items-center justify-between px-3 py-2 rounded-xl text-xs font-medium transition cursor-pointer ${
                        isActive
                          ? 'bg-blue-600/15 text-blue-400 border border-blue-500/30 shadow-sm shadow-blue-500/10'
                          : 'text-neutral-400 hover:text-neutral-200 hover:bg-[#141A26] border border-transparent'
                      }`}
                    >
                      <div className="flex items-center gap-2.5 truncate">
                        <Icon className={`w-4 h-4 shrink-0 ${isActive ? 'text-blue-400' : 'text-neutral-400'}`} />
                        <span className="truncate">{item.label}</span>
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0">
                        {item.subtitle && (
                          <span className="text-[9px] font-mono text-neutral-400 truncate max-w-[70px]">
                            {item.subtitle}
                          </span>
                        )}
                        {item.badge && (
                          <span className={`text-[9px] font-mono px-1.5 py-0.2 rounded-full border ${item.badgeColor || 'bg-neutral-800 text-neutral-400'}`}>
                            {item.badge}
                          </span>
                        )}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>
      </div>

      {/* Footer / System Status */}
      <div className="p-4 border-t border-[#1E2638] space-y-3">
        {/* Status Card */}
        <div className="bg-[#131722] border border-[#1E2638] rounded-xl p-3 text-[11px] space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-neutral-400 flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              API Server
            </span>
            <span className="text-[10px] text-emerald-400 font-mono font-medium">127.0.0.1:8000</span>
          </div>

          <div className="flex items-center justify-between text-[10px] text-neutral-400 pt-1.5 border-t border-[#1E2638]/60">
            <span className="flex items-center gap-1">
              <ShieldCheck className="w-3 h-3 text-emerald-400" /> Audio Lock
            </span>
            <span className={isAudioMuted ? 'text-emerald-400' : 'text-amber-400'}>
              {isAudioMuted ? 'Hardware Muted' : 'Unmuted'}
            </span>
          </div>
        </div>

        {/* Link to Django Admin */}
        <a
          href="http://localhost:8000/admin/"
          target="_blank"
          rel="noreferrer"
          className="flex items-center justify-between w-full px-3 py-2 bg-[#131722] hover:bg-[#1A2030] text-neutral-400 hover:text-white rounded-xl border border-[#1E2638] text-xs transition cursor-pointer"
        >
          <span className="flex items-center gap-2">
            <Layers className="w-3.5 h-3.5 text-indigo-400" />
            Django Admin Menu
          </span>
          <ExternalLink className="w-3 h-3 text-neutral-400" />
        </a>
      </div>
    </aside>
  );
}
