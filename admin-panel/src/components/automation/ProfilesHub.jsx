import React, { useState } from 'react';
import { 
  Users, 
  ShieldCheck, 
  Zap, 
  Search, 
  Sliders, 
  Smartphone, 
  Download, 
  Upload, 
  CheckCircle2, 
  Sparkles,
  Layers,
  Clock,
  Copy
} from 'lucide-react';
import { importProfileCookies, fetchProfiles } from '../../api';

export default function ProfilesHub({
  profiles = [],
  setProfiles,
  onSelectPersona,
  onRefresh,
  showNotification,
  selectedProfileIds = [],
  setSelectedProfileIds,
  onOpenAssistant
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeProfileForImport, setActiveProfileForImport] = useState(null);
  const [importJsonText, setImportJsonText] = useState('');
  const [isSubmittingImport, setIsSubmittingImport] = useState(false);

  const toggleProfile = (id) => {
    if (!setSelectedProfileIds) return;
    setSelectedProfileIds((prev) =>
      prev.includes(id) ? prev.filter((pId) => pId !== id) : [...prev, id]
    );
  };

  const toggleSelectAll = () => {
    if (!setSelectedProfileIds) return;
    const allFilteredIds = filteredProfiles.map((p) => p.id);
    const allSelected = allFilteredIds.length > 0 && allFilteredIds.every((id) => selectedProfileIds.includes(id));
    if (allSelected) {
      setSelectedProfileIds((prev) => prev.filter((id) => !allFilteredIds.includes(id)));
    } else {
      setSelectedProfileIds((prev) => Array.from(new Set([...prev, ...allFilteredIds])));
    }
  };

  const handleDownloadCookies = (profileId) => {
    window.open(`http://localhost:8000/api/profiles/${profileId}/cookies/export/?download=true`, '_blank');
  };

  const handleImportSubmit = async () => {
    if (!activeProfileForImport || !importJsonText.trim()) return;
    setIsSubmittingImport(true);
    try {
      let cookiesPayload = [];
      const trimmed = importJsonText.trim();
      if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        cookiesPayload = JSON.parse(trimmed);
      } else {
        trimmed.split('\n').forEach(line => {
          const l = line.trim();
          if (l && !l.startsWith('#')) {
            const parts = l.split('\t');
            if (parts.length >= 7) {
              cookiesPayload.push({
                domain: parts[0],
                path: parts[2],
                isSecure: parts[3].toUpperCase() === 'TRUE',
                expiry: parseInt(parts[4]) || Math.floor(Date.now() / 1000) + 31536000,
                name: parts[5],
                value: parts[6]
              });
            }
          }
        });
      }

      if (!Array.isArray(cookiesPayload) || cookiesPayload.length === 0) {
        if (showNotification) showNotification('No valid cookies detected.', 'error');
        setIsSubmittingImport(false);
        return;
      }

      await importProfileCookies(activeProfileForImport.id, cookiesPayload);
      if (showNotification) showNotification(`Imported ${cookiesPayload.length} cookies into ${activeProfileForImport.name}!`);
      setImportJsonText('');
      setActiveProfileForImport(null);
      const profRes = await fetchProfiles();
      setProfiles(profRes.data);
    } catch (err) {
      console.error(err);
      if (showNotification) showNotification('Cookie import failed', 'error');
    } finally {
      setIsSubmittingImport(false);
    }
  };

  const totalCookies = profiles.reduce((acc, p) => acc + (p.cookie_count || 0), 0);
  const filteredProfiles = profiles.filter((p) => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return (
      p.name?.toLowerCase().includes(q) ||
      p.brand?.toLowerCase().includes(q) ||
      p.model_name?.toLowerCase().includes(q) ||
      p.model_code?.toLowerCase().includes(q)
    );
  });

  return (
    <div className="space-y-6">
      {/* Metrics Row */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Total Fleet Profiles</span>
            <div className="text-2xl font-bold text-white mt-0.5">{profiles.length}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Users className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Total Synced Cookies</span>
            <div className="text-2xl font-bold text-emerald-400 mt-0.5">{totalCookies}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
            <CheckCircle2 className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Active Fingerprints</span>
            <div className="text-2xl font-bold text-cyan-400 mt-0.5">{profiles.length}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Smartphone className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* Search & Action Bar */}
      <div className="flex flex-col sm:flex-row justify-between items-center gap-3">
        <div className="relative w-full sm:w-80">
          <input
            type="text"
            placeholder="Search profiles by brand, model, code..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-[#111520] border border-[#1E2638] text-xs rounded-xl pl-9 pr-3 py-2.5 text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500 transition"
          />
          <Search className="w-4 h-4 text-neutral-500 absolute left-3 top-3" />
        </div>

        <button
          onClick={onRefresh}
          className="text-xs bg-[#111520] hover:bg-[#181E2E] text-neutral-300 border border-[#1E2638] px-3.5 py-2 rounded-xl transition cursor-pointer self-end sm:self-auto"
        >
          Refresh Fleet List
        </button>
      </div>

      {/* Multi-Selection Control Bar */}
      <div className="flex flex-wrap items-center justify-between bg-[#111520] border border-[#1E2638] px-4 py-2.5 rounded-2xl text-xs gap-3 shadow-md">
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 cursor-pointer text-neutral-300 hover:text-white font-medium select-none">
            <input
              type="checkbox"
              checked={filteredProfiles.length > 0 && filteredProfiles.every((p) => selectedProfileIds.includes(p.id))}
              onChange={toggleSelectAll}
              className="w-4 h-4 rounded bg-[#0A0D14] border-[#2C374E] text-blue-600 focus:ring-blue-500/30 cursor-pointer accent-blue-600"
            />
            <span>Select All Profiles ({filteredProfiles.length})</span>
          </label>
          {selectedProfileIds.length > 0 && (
            <span className="bg-blue-500/20 text-blue-300 border border-blue-500/30 px-2.5 py-0.5 rounded-full font-mono font-bold text-[11px]">
              {selectedProfileIds.length} selected
            </span>
          )}
        </div>

        {selectedProfileIds.length > 0 && (
          <div className="flex items-center gap-2.5">
            <button
              onClick={() => setSelectedProfileIds && setSelectedProfileIds([])}
              className="text-neutral-400 hover:text-white transition px-2 py-1 cursor-pointer text-xs"
            >
              Clear Selection
            </button>
            <button
              onClick={onOpenAssistant}
              className="bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white font-semibold text-xs px-3.5 py-1.5 rounded-xl flex items-center gap-1.5 shadow-md shadow-blue-500/25 transition cursor-pointer"
            >
              <Sparkles className="w-3.5 h-3.5" />
              <span>Ask AI Copilot to Execute Task ({selectedProfileIds.length})</span>
            </button>
          </div>
        )}
      </div>

      {/* Profiles Grid */}
      {filteredProfiles.length === 0 ? (
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-12 text-center text-neutral-400">
          <Users className="w-10 h-10 mx-auto mb-3 opacity-30 text-neutral-400" />
          <p className="text-sm font-semibold text-white">No Profiles Found</p>
          <p className="text-xs text-neutral-500 mt-1">Generate a profile in Hardware Blueprints or add one via the Android app.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {filteredProfiles.map((p) => {
            const isSelected = selectedProfileIds.includes(p.id);
            return (
              <div
                key={p.id}
                className={`border rounded-2xl p-5 flex flex-col justify-between shadow-xl transition-all space-y-4 ${
                  isSelected
                    ? 'bg-[#141926] border-blue-500/60 ring-1 ring-blue-500/30'
                    : 'bg-[#111520] border-[#1E2638] hover:border-[#2C374E]'
                }`}
              >
                <div>
                  {/* Profile Header */}
                  <div className="flex justify-between items-start">
                    <div className="flex items-center gap-2.5">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => toggleProfile(p.id)}
                        className="w-4 h-4 rounded bg-[#0A0D14] border-[#2C374E] text-blue-600 focus:ring-blue-500/30 cursor-pointer accent-blue-600 mt-0.5"
                      />
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="w-2.5 h-2.5 rounded-full bg-emerald-400" />
                          <h3 className="font-bold text-sm text-white truncate max-w-[150px]">{p.name}</h3>
                        </div>
                        <p className="text-xs text-neutral-400 mt-0.5">{p.brand} {p.model_name}</p>
                      </div>
                    </div>
                    <span className="text-[10px] bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 px-2 py-0.5 rounded-full font-semibold font-mono">
                      {p.cookie_count || 0} cookies
                    </span>
                  </div>

                  {/* Profile ID Badge with 1-Click Copy */}
                  <div className="mt-3 flex items-center justify-between bg-[#0B0E17] border border-[#1E2638] px-2.5 py-1.5 rounded-xl">
                    <div className="flex items-center gap-1.5 min-w-0">
                      <span className="text-[9px] font-bold text-neutral-500 uppercase tracking-wider font-mono">ID:</span>
                      <span className="text-[11px] font-mono text-cyan-300 font-semibold truncate" title={p.id}>
                        {p.id}
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigator.clipboard.writeText(p.id);
                        if (showNotification) showNotification(`Copied Profile ID: ${p.id.slice(0, 8)}...`);
                      }}
                      className="shrink-0 ml-2 text-neutral-400 hover:text-white bg-[#181E2E] hover:bg-[#232A3E] border border-[#1E2638] px-2 py-0.5 rounded-lg text-[10px] flex items-center gap-1 transition cursor-pointer font-medium"
                      title="Copy full Profile UUID"
                    >
                      <Copy className="w-3 h-3 text-cyan-400" />
                      <span>Copy</span>
                    </button>
                  </div>

                {/* Specs Pill Matrix */}
                <div className="mt-3.5 pt-3 border-t border-[#1E2638]/70 grid grid-cols-2 gap-2 text-[11px]">
                  <div>
                    <span className="text-neutral-500 block text-[10px]">Model Code</span>
                    <span className="text-neutral-300 font-mono text-[10px]">{p.model_code || 'Unknown'}</span>
                  </div>
                  <div>
                    <span className="text-neutral-500 block text-[10px]">Proxy Route</span>
                    <span className="text-neutral-300 font-mono text-[10px]">{p.proxy_type || 'DIRECT'}</span>
                  </div>
                  <div>
                    <span className="text-neutral-500 block text-[10px]">Display / DPR</span>
                    <span className="text-neutral-300 font-mono text-[10px]">{p.screen_width}x{p.screen_height} ({p.dpr}x)</span>
                  </div>
                  <div>
                    <span className="text-neutral-500 block text-[10px]">Memory</span>
                    <span className="text-neutral-300 font-mono text-[10px]">{p.ram_gb || 8}GB RAM</span>
                  </div>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="pt-3 border-t border-[#1E2638]/70 space-y-2">
                <button
                  onClick={() => onSelectPersona(p)}
                  className="w-full bg-blue-600/15 hover:bg-blue-600/25 text-blue-400 border border-blue-500/30 text-xs font-semibold py-2 px-3 rounded-xl flex items-center justify-center gap-1.5 transition cursor-pointer"
                >
                  <Sliders className="w-3.5 h-3.5" />
                  <span>Configure Behavioral Persona & Niches</span>
                </button>

                <div className="flex gap-2">
                  <button
                    onClick={() => handleDownloadCookies(p.id)}
                    className="flex-1 bg-[#181E2E] hover:bg-[#232A3E] text-neutral-300 hover:text-white border border-[#1E2638] text-[11px] py-1.5 px-2 rounded-lg flex items-center justify-center gap-1 transition cursor-pointer"
                  >
                    <Download className="w-3 h-3 text-cyan-400" />
                    <span>Export Cookies</span>
                  </button>
                  <button
                    onClick={() => {
                      setActiveProfileForImport(p);
                      setImportJsonText('');
                    }}
                    className="flex-1 bg-[#181E2E] hover:bg-[#232A3E] text-neutral-300 hover:text-white border border-[#1E2638] text-[11px] py-1.5 px-2 rounded-lg flex items-center justify-center gap-1 transition cursor-pointer"
                  >
                    <Upload className="w-3 h-3 text-emerald-400" />
                    <span>Import Cookies</span>
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </div>
      )}

      {/* Universal Cookie Importer Modal */}
      {activeProfileForImport && (
        <div className="fixed inset-0 bg-black/75 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-6 max-w-xl w-full shadow-2xl space-y-4">
            <div className="flex justify-between items-center border-b border-[#1E2638] pb-3">
              <div>
                <h3 className="text-base font-semibold text-white">Import Cookies: {activeProfileForImport.name}</h3>
                <p className="text-xs text-neutral-400">Paste Netscape tab-separated lines or standard JSON cookie array</p>
              </div>
              <button
                onClick={() => setActiveProfileForImport(null)}
                className="text-neutral-400 hover:text-white text-lg font-bold px-2 cursor-pointer"
              >
                ×
              </button>
            </div>

            <textarea
              rows={8}
              value={importJsonText}
              onChange={(e) => setImportJsonText(e.target.value)}
              placeholder='[&#10;  {"name": "session_id", "value": "xyz...", "domain": ".google.com", "path": "/"}&#10;]'
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-3 text-xs font-mono text-neutral-200 focus:outline-none focus:border-cyan-500/60 leading-relaxed"
            />

            <div className="flex justify-end gap-2">
              <button
                onClick={() => setActiveProfileForImport(null)}
                className="px-4 py-2 text-xs bg-neutral-800 hover:bg-neutral-700 text-neutral-300 rounded-lg cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleImportSubmit}
                disabled={isSubmittingImport || !importJsonText.trim()}
                className="px-4 py-2 text-xs bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white rounded-lg font-medium cursor-pointer"
              >
                {isSubmittingImport ? 'Importing...' : 'Save & Overwrite Cookies'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
