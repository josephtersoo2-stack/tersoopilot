import React, { useState, useEffect } from 'react';
import axios from '../../api';
import { Tag, Globe, Youtube, Plus, Trash2, Save, Layers, Sparkles } from 'lucide-react';

const API_NICHES = 'automation/niches/';

export default function NichesHub() {
  const [niches, setNiches] = useState([]);
  const [selectedNiche, setSelectedNiche] = useState(null);
  const [formData, setFormData] = useState({
    name: '',
    description: '',
    seed_keywords: '',
    seed_websites: '',
    target_youtube_channels: ''
  });

  useEffect(() => {
    loadNiches();
  }, []);

  const loadNiches = async () => {
    try {
      const res = await axios.get(API_NICHES);
      setNiches(res.data);
      if (res.data.length > 0 && !selectedNiche) {
        selectNiche(res.data[0]);
      }
    } catch (err) {
      console.error('Failed to load niches:', err);
    }
  };

  const selectNiche = (niche) => {
    setSelectedNiche(niche);
    setFormData({
      name: niche.name,
      description: niche.description || '',
      seed_keywords: (niche.seed_keywords || []).join(', '),
      seed_websites: (niche.seed_websites || []).join(', '),
      target_youtube_channels: (niche.target_youtube_channels || []).join(', ')
    });
  };

  const handleCreateNew = () => {
    setSelectedNiche(null);
    setFormData({
      name: '',
      description: '',
      seed_keywords: '',
      seed_websites: '',
      target_youtube_channels: ''
    });
  };

  const handleSave = async () => {
    const payload = {
      name: formData.name,
      description: formData.description,
      seed_keywords: formData.seed_keywords.split(',').map(s => s.trim()).filter(Boolean),
      seed_websites: formData.seed_websites.split(',').map(s => s.trim()).filter(Boolean),
      target_youtube_channels: formData.target_youtube_channels.split(',').map(s => s.trim()).filter(Boolean)
    };

    try {
      if (selectedNiche) {
        await axios.put(`${API_NICHES}${selectedNiche.id}/`, payload);
      } else {
        await axios.post(API_NICHES, payload);
      }
      loadNiches();
    } catch (err) {
      alert('Error saving niche: ' + (err.response?.data?.name?.[0] || err.message));
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this niche?')) return;
    try {
      await axios.delete(`${API_NICHES}${id}/`);
      setSelectedNiche(null);
      loadNiches();
    } catch (err) {
      console.error(err);
    }
  };

  const keywordsList = formData.seed_keywords.split(',').map(s => s.trim()).filter(Boolean);
  const websitesList = formData.seed_websites.split(',').map(s => s.trim()).filter(Boolean);
  const channelsList = formData.target_youtube_channels.split(',').map(s => s.trim()).filter(Boolean);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
      {/* Left List: Niches Explorer (4 cols) */}
      <div className="lg:col-span-4 bg-[#111520] border border-[#1E2638] rounded-2xl p-4 space-y-3 shadow-xl">
        <div className="flex justify-between items-center pb-3 border-b border-[#1E2638]">
          <div className="flex items-center gap-2">
            <Layers className="w-4 h-4 text-purple-400" />
            <h2 className="text-sm font-bold text-white">Target Niches ({niches.length})</h2>
          </div>
          <button
            onClick={handleCreateNew}
            className="flex items-center gap-1 text-xs bg-blue-600 hover:bg-blue-500 text-white font-semibold px-2.5 py-1.5 rounded-xl transition cursor-pointer shadow-sm shadow-blue-600/20"
          >
            <Plus className="w-3.5 h-3.5" /> New Niche
          </button>
        </div>

        <div className="space-y-2 max-h-[600px] overflow-y-auto pr-1">
          {niches.length === 0 ? (
            <p className="text-xs text-neutral-500 py-6 text-center">No niches created yet.</p>
          ) : (
            niches.map((n) => {
              const isSelected = selectedNiche?.id === n.id;
              return (
                <div
                  key={n.id}
                  onClick={() => selectNiche(n)}
                  className={`p-3.5 rounded-xl cursor-pointer border transition-all ${
                    isSelected
                      ? 'bg-purple-600/10 border-purple-500/50 shadow-md shadow-purple-500/10'
                      : 'bg-[#0A0D14] border-[#1E2638] hover:border-[#2C374E]'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="font-semibold text-xs text-white">{n.name}</span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDelete(n.id);
                      }}
                      className="text-neutral-500 hover:text-rose-400 p-1 transition cursor-pointer"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  <p className="text-[11px] text-neutral-400 line-clamp-1">{n.description || 'No description notes'}</p>
                  
                  <div className="flex items-center gap-2 mt-2 pt-2 border-t border-[#1E2638]/60 text-[10px] text-neutral-500">
                    <span>{n.seed_keywords?.length || 0} keywords</span>
                    <span>•</span>
                    <span>{n.seed_websites?.length || 0} sites</span>
                    <span>•</span>
                    <span>{n.target_youtube_channels?.length || 0} channels</span>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* Right Form: Niche Editor (8 cols) */}
      <div className="lg:col-span-8 bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-5">
        <div className="flex justify-between items-center pb-4 border-b border-[#1E2638]">
          <div>
            <h2 className="text-base font-bold text-white">
              {selectedNiche ? `Edit Niche: ${selectedNiche.name}` : 'Create New Target Niche'}
            </h2>
            <p className="text-xs text-neutral-400 mt-0.5">
              Defines the semantic domain, search topics, and competitor channels used during autonomous browsing.
            </p>
          </div>
          <button
            onClick={handleSave}
            className="flex items-center gap-1.5 bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold px-4 py-2 rounded-xl transition cursor-pointer shadow-md shadow-blue-600/25"
          >
            <Save className="w-4 h-4" /> Save Niche
          </button>
        </div>

        <div className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-neutral-300 mb-1.5">Niche Name</label>
              <input
                type="text"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                placeholder="e.g. Consumer Electronics & Keyboards"
                className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-2.5 text-xs text-white focus:border-purple-500 outline-none transition"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-neutral-300 mb-1.5">Description / Target Audience</label>
              <input
                type="text"
                value={formData.description}
                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                placeholder="Operational notes regarding this audience domain"
                className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-2.5 text-xs text-white focus:border-purple-500 outline-none transition"
              />
            </div>
          </div>

          <div>
            <div className="flex justify-between items-center mb-1.5">
              <label className="flex items-center gap-1.5 text-xs font-medium text-neutral-300">
                <Tag className="w-3.5 h-3.5 text-blue-400" /> Seed Keywords (comma-separated)
              </label>
              <span className="text-[10px] text-neutral-500 font-mono">{keywordsList.length} keywords</span>
            </div>
            <textarea
              rows={3}
              value={formData.seed_keywords}
              onChange={(e) => setFormData({ ...formData, seed_keywords: e.target.value })}
              placeholder="mechanical keyboards, OLED monitor test, smartphone camera review"
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-2.5 text-xs font-mono text-neutral-200 focus:border-blue-500 outline-none leading-relaxed"
            />
            {keywordsList.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {keywordsList.map((kw, i) => (
                  <span key={i} className="text-[10px] bg-blue-500/10 text-blue-300 border border-blue-500/20 px-2 py-0.5 rounded-md">
                    #{kw}
                  </span>
                ))}
              </div>
            )}
          </div>

          <div>
            <div className="flex justify-between items-center mb-1.5">
              <label className="flex items-center gap-1.5 text-xs font-medium text-neutral-300">
                <Globe className="w-3.5 h-3.5 text-emerald-400" /> Authority Seed Websites (comma-separated)
              </label>
              <span className="text-[10px] text-neutral-500 font-mono">{websitesList.length} domains</span>
            </div>
            <textarea
              rows={2}
              value={formData.seed_websites}
              onChange={(e) => setFormData({ ...formData, seed_websites: e.target.value })}
              placeholder="theverge.com, rtings.com, tomshardware.com"
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-2.5 text-xs font-mono text-emerald-300 focus:border-emerald-500 outline-none leading-relaxed"
            />
            {websitesList.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {websitesList.map((wb, i) => (
                  <span key={i} className="text-[10px] bg-emerald-500/10 text-emerald-300 border border-emerald-500/20 px-2 py-0.5 rounded-md">
                    🌐 {wb}
                  </span>
                ))}
              </div>
            )}
          </div>

          <div>
            <div className="flex justify-between items-center mb-1.5">
              <label className="flex items-center gap-1.5 text-xs font-medium text-neutral-300">
                <Youtube className="w-3.5 h-3.5 text-red-500" /> Target Competitor Channels (comma-separated)
              </label>
              <span className="text-[10px] text-neutral-500 font-mono">{channelsList.length} channels</span>
            </div>
            <textarea
              rows={2}
              value={formData.target_youtube_channels}
              onChange={(e) => setFormData({ ...formData, target_youtube_channels: e.target.value })}
              placeholder="@MKBHD, @Dave2D, @LinusTechTips"
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-2.5 text-xs font-mono text-red-300 focus:border-red-500 outline-none leading-relaxed"
            />
            {channelsList.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {channelsList.map((ch, i) => (
                  <span key={i} className="text-[10px] bg-red-500/10 text-red-300 border border-red-500/20 px-2 py-0.5 rounded-md">
                    ▶ {ch}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
