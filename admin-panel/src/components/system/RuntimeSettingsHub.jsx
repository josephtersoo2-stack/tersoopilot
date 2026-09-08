import React from 'react';
import { 
  Sliders, 
  VolumeX, 
  Volume2, 
  Tv, 
  Smartphone, 
  ShieldCheck, 
  AlertTriangle,
  Radio,
  Cpu
} from 'lucide-react';
import { updateGlobalSettings } from '../../api';

export default function RuntimeSettingsHub({
  settings,
  setSettings,
  profilesCount = 0,
  showNotification
}) {
  const handleSettingChange = async (updates) => {
    try {
      const res = await updateGlobalSettings(updates);
      setSettings(res.data);
      if (showNotification) showNotification('Global settings updated and synced to Android fleet.');
    } catch (err) {
      if (showNotification) showNotification('Failed to update settings', 'error');
    }
  };

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      {/* Metrics Banner */}
      <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Concurrency Limit</span>
            <div className="text-2xl font-bold text-blue-400 mt-0.5">{settings.max_active_profiles} / 10</div>
          </div>
          <div className="p-2.5 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Sliders className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Audio Sink Lock</span>
            <div className="text-sm font-bold text-emerald-400 mt-1">
              {settings.force_global_mute ? 'Hardware Muted' : 'Unmuted'}
            </div>
          </div>
          <div className={`p-2.5 rounded-xl border ${
            settings.force_global_mute 
              ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400' 
              : 'bg-amber-500/10 border-amber-500/20 text-amber-400'
          }`}>
            {settings.force_global_mute ? <VolumeX className="w-5 h-5" /> : <Volume2 className="w-5 h-5" />}
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Video Clamp</span>
            <div className="text-2xl font-bold text-purple-400 mt-0.5">{settings.default_video_resolution}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-purple-400">
            <Tv className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Fleet Profiles</span>
            <div className="text-2xl font-bold text-white mt-0.5">{profilesCount}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Smartphone className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* Main Settings Section */}
      <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-6">
        <div className="flex items-center gap-3 pb-4 border-b border-[#1E2638]">
          <div className="p-2 bg-blue-500/10 rounded-lg text-blue-400">
            <Sliders className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">Runtime Constraints & Fleet Concurrency</h2>
            <p className="text-xs text-neutral-400">Orchestrate low-level browser engine parameters and memory bounds across all devices</p>
          </div>
        </div>

        {/* Setting 1: Concurrency Slider */}
        <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-5 space-y-3">
          <div className="flex justify-between items-center">
            <div>
              <label className="text-sm font-semibold text-white">
                Max Active Profiles Concurrency
              </label>
              <p className="text-xs text-neutral-400 mt-0.5">
                Controls the maximum concurrent background GeckoSessions allowed before queuing.
              </p>
            </div>
            <span className="text-sm font-bold text-blue-400 bg-blue-500/10 px-3 py-1 rounded-lg border border-blue-500/20 font-mono">
              {settings.max_active_profiles} / 10 Active
            </span>
          </div>

          <input
            type="range"
            min="1"
            max="10"
            value={settings.max_active_profiles}
            onChange={(e) => handleSettingChange({ max_active_profiles: parseInt(e.target.value) })}
            className="w-full h-2.5 bg-[#1E2638] rounded-lg appearance-none cursor-pointer accent-blue-500"
          />

          <div className="flex justify-between text-[11px] text-neutral-500 font-mono">
            <span>1 (Single Session)</span>
            <span>5 (Balanced Fleet)</span>
            <span>10 (Maximum Hardware Load)</span>
          </div>

          <div className="bg-[#111520] rounded-lg p-3 text-xs text-neutral-400 flex items-start gap-2 border border-[#1E2638]/60">
            <Cpu className="w-4 h-4 text-blue-400 shrink-0 mt-0.5" />
            <span>
              Throttles parallel sessions to avoid Android OOM (Out Of Memory) crashes and hardware CPU throttling when running high-load tasks.
            </span>
          </div>
        </div>

        {/* Setting 2: Force Global Mute */}
        <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-5 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-white">Force Global Mute (Mutual Exclusion)</span>
              <span className="text-[10px] bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-semibold px-2 py-0.5 rounded-full">
                Bypass-Proof
              </span>
            </div>
            <p className="text-xs text-neutral-400 max-w-xl">
              Enforces hardware-level audio sink silence (`media.volume_scale=0.0`) and locks `HTMLMediaElement.prototype.muted` so unmuting scripts cannot play unwanted audio.
            </p>
          </div>

          <label className="relative inline-flex items-center cursor-pointer shrink-0">
            <input
              type="checkbox"
              checked={settings.force_global_mute}
              onChange={(e) => handleSettingChange({ force_global_mute: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-[#1E2638] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-neutral-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-blue-600"></div>
          </label>
        </div>

        {/* Setting 3: Video Stream Resolution */}
        <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-5 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
          <div className="space-y-1">
            <span className="text-sm font-semibold text-white">Enforce Video Stream Resolution</span>
            <p className="text-xs text-neutral-400 max-w-xl">
              Clamps streaming video feeds (YouTube, Twitch, HTML5) to minimal resolution to preserve mobile hardware MediaCodec decoder instances.
            </p>
          </div>

          <select
            value={settings.default_video_resolution}
            onChange={(e) => handleSettingChange({ default_video_resolution: e.target.value })}
            className="bg-[#111520] border border-[#1E2638] text-xs font-semibold rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-blue-500 cursor-pointer shrink-0"
          >
            <option value="240p">240p (Optimal Fleet Quality)</option>
            <option value="144p">144p (Ultra-Light Bandwidth)</option>
            <option value="360p">360p (Higher Decoder Load)</option>
          </select>
        </div>
      </div>
    </div>
  );
}
