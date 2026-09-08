import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Play, Youtube, Globe, X, Sliders, MessageSquare, ThumbsUp, UserCheck, FastForward, Film } from 'lucide-react';

export default function TaskDispatchModal({ profiles = [], onClose, onDispatched }) {
  const [taskCategory, setTaskCategory] = useState('YOUTUBE');
  const [taskName, setTaskName] = useState('YouTube Targeted Growth Campaign');
  const [selectedProfileIds, setSelectedProfileIds] = useState([]);
  const [niches, setNiches] = useState([]);
  const [selectedNicheId, setSelectedNicheId] = useState('');

  // YouTube Strategy Selectors
  const [ytStrategy, setYtStrategy] = useState('SEARCH_TARGET');
  const [targetKeyword, setTargetKeyword] = useState('');
  const [targetVideoUrl, setTargetVideoUrl] = useState('');
  const [targetVideoTitle, setTargetVideoTitle] = useState('');
  const [targetChannel, setTargetChannel] = useState('');
  const [targetUrl, setTargetUrl] = useState('');
  const [maxSearchScroll, setMaxSearchScroll] = useState(10);
  const [shortsCount, setShortsCount] = useState(8);
  const [rabbitHoleDepth, setRabbitHoleDepth] = useState(3);
  const [videoFormat, setVideoFormat] = useState('long_form'); // 'long_form', 'shorts', 'both'

  // Playback & Watch Retention
  const [retentionMode, setRetentionMode] = useState('DURATION');
  const [minWatch, setMinWatch] = useState(90);
  const [maxWatch, setMaxWatch] = useState(240);

  // Engagement Toggles
  const [enableComments, setEnableComments] = useState(true);
  const [likeProb, setLikeProb] = useState(35);
  const [subProb, setSubProb] = useState(15);
  const [enableScrubbing, setEnableScrubbing] = useState(true);
  const [enableSpeedToggle, setEnableSpeedToggle] = useState(false);

  useEffect(() => {
    axios.get('http://localhost:8000/api/automation/niches/').then((res) => {
      setNiches(res.data);
      if (res.data.length > 0) setSelectedNicheId(res.data[0].id);
    }).catch(err => console.error('Failed to load niches for dispatch:', err));
  }, []);

  const toggleSelectProfile = (id) => {
    setSelectedProfileIds((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id]
    );
  };

  const selectAllProfiles = () => {
    if (selectedProfileIds.length === profiles.length) {
      setSelectedProfileIds([]);
    } else {
      setSelectedProfileIds(profiles.map((p) => p.id));
    }
  };

  const handleDispatch = async () => {
    if (selectedProfileIds.length === 0) {
      alert('Select at least one profile.');
      return;
    }

    const candidateKeywords = targetKeyword
      .split(/[\n,]/)
      .map((k) => k.trim())
      .filter(Boolean);

    const taskPayload = {
      name: taskName,
      category: taskCategory,
      niche: selectedNicheId || null,
      config: {
        strategy: ytStrategy,
        video_format: videoFormat,
        target_keywords: candidateKeywords,
        target_keyword: candidateKeywords[0] || '',
        target_video_url: targetVideoUrl,
        target_video_title: targetVideoTitle,
        target_channel: targetChannel,
        target_url: targetUrl,
        max_search_scroll_depth: parseInt(maxSearchScroll) || 10,
        shorts_count: parseInt(shortsCount) || 8,
        rabbit_hole_depth: parseInt(rabbitHoleDepth) || 3,
        retention_mode: retentionMode,
        min_watch_seconds: parseInt(minWatch) || 90,
        max_watch_seconds: parseInt(maxWatch) || 240,
        enable_comments: enableComments,
        like_probability: parseFloat(likeProb) / 100,
        subscribe_probability: parseFloat(subProb) / 100,
        enable_scrubbing: enableScrubbing,
        enable_speed_toggle: enableSpeedToggle
      }
    };

    try {
      const taskRes = await axios.post('http://localhost:8000/api/automation/tasks/', taskPayload);
      const taskId = taskRes.data.id;

      const dispatchRes = await axios.post(`http://localhost:8000/api/automation/tasks/${taskId}/dispatch/`, {
        profile_ids: selectedProfileIds
      });

      alert(`Successfully dispatched ${dispatchRes.data.dispatched_count} calibrated DAG task(s)!`);
      if (onDispatched) onDispatched();
      onClose();
    } catch (err) {
      alert('Dispatch failed: ' + (err.response?.data?.error || err.message));
    }
  };

  const parsedKeywords = targetKeyword
    .split(/[\n,]/)
    .map((k) => k.trim())
    .filter(Boolean);

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
      <div className="bg-neutral-900 border border-neutral-800 rounded-2xl w-full max-w-3xl p-6 relative max-h-[92vh] overflow-y-auto shadow-2xl">
        <button onClick={onClose} className="absolute top-4 right-4 text-neutral-400 hover:text-white cursor-pointer">
          <X className="w-5 h-5" />
        </button>

        <h2 className="text-lg font-bold text-white mb-1">Create & Dispatch Automation Campaign</h2>
        <p className="text-xs text-neutral-400 mb-5">Configure platform commands, human behavior toggles, and targeting strategies.</p>

        <div className="space-y-4">
          {/* Top Category Tabs */}
          <div className="grid grid-cols-2 gap-3">
            <button
              onClick={() => {
                setTaskCategory('YOUTUBE');
                setTaskName('YouTube Advanced Suite Campaign');
              }}
              className={`p-3 rounded-xl border flex items-center justify-center gap-2 text-xs font-semibold cursor-pointer transition ${
                taskCategory === 'YOUTUBE'
                  ? 'bg-red-950/40 border-red-500 text-red-400'
                  : 'bg-neutral-950 border-neutral-800 text-neutral-400 hover:border-neutral-700'
              }`}
            >
              <Youtube className="w-4 h-4" /> YouTube Advanced Suite
            </button>
            <button
              onClick={() => {
                setTaskCategory('WARMING');
                setTaskName('Multi-Phase Web Cookie Warmer');
              }}
              className={`p-3 rounded-xl border flex items-center justify-center gap-2 text-xs font-semibold cursor-pointer transition ${
                taskCategory === 'WARMING'
                  ? 'bg-emerald-950/40 border-emerald-500 text-emerald-400'
                  : 'bg-neutral-950 border-neutral-800 text-neutral-400 hover:border-neutral-700'
              }`}
            >
              <Globe className="w-4 h-4" /> Cookie Warmer (Web Entropy)
            </button>
          </div>

          {/* Core Settings */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-neutral-400 mb-1">Campaign Title</label>
              <input
                type="text"
                value={taskName}
                onChange={(e) => setTaskName(e.target.value)}
                className="w-full bg-neutral-950 border border-neutral-800 rounded-lg p-2.5 text-xs text-white outline-none focus:border-blue-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-neutral-400 mb-1">Target Niche</label>
              <select
                value={selectedNicheId}
                onChange={(e) => setSelectedNicheId(e.target.value)}
                className="w-full bg-neutral-950 border border-neutral-800 rounded-lg p-2.5 text-xs text-white outline-none focus:border-blue-500"
              >
                {niches.length === 0 ? (
                  <option value="">No niches defined</option>
                ) : (
                  niches.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))
                )}
              </select>
            </div>
          </div>

          {/* YouTube Advanced Configuration */}
          {taskCategory === 'YOUTUBE' && (
            <div className="bg-neutral-950 border border-neutral-800 rounded-xl p-5 space-y-4">
              {/* Strategy Selector */}
              <div>
                <label className="block text-xs font-semibold text-neutral-300 mb-1.5 flex items-center gap-1.5">
                  <Sliders className="w-3.5 h-3.5 text-red-400" /> Operational Strategy
                </label>
                <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
                  {[
                    { id: 'SEARCH_TARGET', label: 'Search & Match', icon: '🔍' },
                    { id: 'SHORTS_SURF', label: 'Shorts Surfing', icon: '⚡' },
                    { id: 'RABBIT_HOLE', label: 'Rabbit Hole', icon: '🕳️' },
                    { id: 'CHANNEL_BINGE', label: 'Channel Binge', icon: '📺' },
                    { id: 'DIRECT_URL', label: 'Direct Video', icon: '🔗' }
                  ].map((s) => (
                    <button
                      key={s.id}
                      type="button"
                      onClick={() => setYtStrategy(s.id)}
                      className={`p-2 rounded-lg border text-center text-[11px] font-medium transition-all cursor-pointer flex flex-col items-center gap-1 ${
                        ytStrategy === s.id
                          ? 'bg-red-950/60 border-red-500 text-white font-bold ring-1 ring-red-500/40'
                          : 'bg-neutral-900 border-neutral-800 text-neutral-400 hover:text-white hover:border-neutral-700'
                      }`}
                    >
                      <span className="text-sm">{s.icon}</span>
                      <span>{s.label}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Dynamic Target Inputs Based on Strategy */}
              {ytStrategy === 'SEARCH_TARGET' && (
                <div className="space-y-3.5 pt-2 border-t border-neutral-900">
                  {/* Candidate Keywords (Sequential Trial) */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <label className="block text-[11px] font-semibold text-neutral-300">
                        Candidate Search Keywords (Tested One by One)
                      </label>
                      <span className="text-[10px] text-neutral-500">
                        Separate by commas or new lines
                      </span>
                    </div>
                    <textarea
                      rows={2}
                      value={targetKeyword}
                      onChange={(e) => setTargetKeyword(e.target.value)}
                      placeholder="e.g. mechanical keyboard review&#10;budget thock keyboard&#10;custom mechanical keyboard build"
                      className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white placeholder:text-neutral-600 focus:border-blue-500 outline-none"
                    />
                    {parsedKeywords.length > 0 && (
                      <div className="mt-1.5 space-y-1">
                        <div className="flex flex-wrap gap-1.5">
                          {parsedKeywords.map((kw, idx) => (
                            <span
                              key={idx}
                              className="bg-blue-950/70 border border-blue-800/60 text-blue-300 px-2 py-0.5 rounded-md text-[10px] font-mono flex items-center gap-1"
                            >
                              <span className="text-blue-500 font-bold">#{idx + 1}</span> {kw}
                            </span>
                          ))}
                        </div>
                        <p className="text-[10px] text-neutral-400 italic">
                          GhostPilot will search with keyword #1 first. If the video is found, it clicks it and ignores remaining keywords. If not found within scroll depth, it tries #2, etc.
                        </p>
                      </div>
                    )}
                  </div>

                  {/* Target Video Link / URL for Ground Truth DOM Matching */}
                  <div>
                    <label className="block text-[11px] font-semibold text-neutral-300 mb-1 flex items-center justify-between">
                      <span>Target Video Link / URL (Exact Search Matcher)</span>
                      <span className="text-[10px] text-emerald-400 font-mono">Organic Search + Exact DOM Verification</span>
                    </label>
                    <input
                      type="text"
                      value={targetVideoUrl}
                      onChange={(e) => setTargetVideoUrl(e.target.value)}
                      placeholder="e.g. https://www.youtube.com/watch?v=dQw4w9WgXcQ or https://youtu.be/dQw4w9WgXcQ"
                      className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white placeholder:text-neutral-600 focus:border-emerald-500 outline-none"
                    />
                    <p className="text-[10px] text-neutral-500 mt-0.5">
                      GhostPilot searches YouTube using the keywords above, but uses this link/ID to locate the exact video card in search results and scroll directly to it.
                    </p>
                  </div>

                  {/* Secondary Matching Fallbacks */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="block text-[11px] text-neutral-400 mb-1">Target Channel Handle (Fallback)</label>
                      <input
                        type="text"
                        value={targetChannel}
                        onChange={(e) => setTargetChannel(e.target.value)}
                        placeholder="e.g. @MKBHD or Channel Name"
                        className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white placeholder:text-neutral-600"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] text-neutral-400 mb-1">Target Video Title Snippet (Fallback)</label>
                      <input
                        type="text"
                        value={targetVideoTitle}
                        onChange={(e) => setTargetVideoTitle(e.target.value)}
                        placeholder="e.g. Best Budget Thock Board"
                        className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white placeholder:text-neutral-600"
                      />
                    </div>
                  </div>

                  {/* Deep Scroll & Format Filter */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-[11px] text-neutral-400 font-medium">
                          Max Deep Search Scroll Depth
                        </label>
                        <span className="text-[11px] text-blue-400 font-bold">{maxSearchScroll} scrolls</span>
                      </div>
                      <input
                        type="range"
                        min="2"
                        max="25"
                        value={maxSearchScroll}
                        onChange={(e) => setMaxSearchScroll(e.target.value)}
                        className="w-full accent-blue-500 cursor-pointer"
                      />
                      <p className="text-[10px] text-neutral-500 mt-0.5">
                        Scrolls down feed searching for target video before advancing to next keyword.
                      </p>
                    </div>

                    <div>
                      <label className="block text-[11px] text-neutral-400 mb-1 font-medium">
                        Video Format Filter
                      </label>
                      <div className="grid grid-cols-3 gap-1.5">
                        <button
                          type="button"
                          onClick={() => setVideoFormat('long_form')}
                          className={`p-1.5 rounded-lg border text-center transition cursor-pointer text-[10px] ${
                            videoFormat === 'long_form'
                              ? 'bg-blue-950/60 border-blue-500 text-blue-300 font-semibold'
                              : 'bg-neutral-900 border-neutral-800 text-neutral-400 hover:border-neutral-700'
                          }`}
                        >
                          🎬 Long-Form
                        </button>
                        <button
                          type="button"
                          onClick={() => setVideoFormat('shorts')}
                          className={`p-1.5 rounded-lg border text-center transition cursor-pointer text-[10px] ${
                            videoFormat === 'shorts'
                              ? 'bg-red-950/60 border-red-500 text-red-300 font-semibold'
                              : 'bg-neutral-900 border-neutral-800 text-neutral-400 hover:border-neutral-700'
                          }`}
                        >
                          ⚡ Shorts
                        </button>
                        <button
                          type="button"
                          onClick={() => setVideoFormat('both')}
                          className={`p-1.5 rounded-lg border text-center transition cursor-pointer text-[10px] ${
                            videoFormat === 'both'
                              ? 'bg-purple-950/60 border-purple-500 text-purple-300 font-semibold'
                              : 'bg-neutral-900 border-neutral-800 text-neutral-400 hover:border-neutral-700'
                          }`}
                        >
                          🔀 Mixed
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {ytStrategy === 'SHORTS_SURF' && (
                <div className="pt-2 border-t border-neutral-900">
                  <label className="block text-[11px] text-neutral-400 mb-1 font-medium">
                    Shorts Loop Count (Number of Reels to Surf)
                  </label>
                  <div className="flex items-center gap-3">
                    <input
                      type="number"
                      min="3"
                      max="30"
                      value={shortsCount}
                      onChange={(e) => setShortsCount(e.target.value)}
                      className="w-32 bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                    />
                    <span className="text-[11px] text-neutral-500">Each reel is dwelled between 3s and 32s organically</span>
                  </div>
                </div>
              )}

              {ytStrategy === 'RABBIT_HOLE' && (
                <div className="pt-2 border-t border-neutral-900">
                  <label className="block text-[11px] text-neutral-400 mb-1 font-medium">
                    Rabbit Hole Depth (Chained Up-Next Recommendations)
                  </label>
                  <div className="flex items-center gap-3">
                    <input
                      type="number"
                      min="1"
                      max="6"
                      value={rabbitHoleDepth}
                      onChange={(e) => setRabbitHoleDepth(e.target.value)}
                      className="w-32 bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                    />
                    <span className="text-[11px] text-neutral-500">Sequential recommendation clicks simulating binge-watching</span>
                  </div>
                </div>
              )}

              {ytStrategy === 'CHANNEL_BINGE' && (
                <div className="pt-2 border-t border-neutral-900">
                  <label className="block text-[11px] text-neutral-400 mb-1 font-medium">Target Channel Handle or URL</label>
                  <input
                    type="text"
                    value={targetChannel}
                    onChange={(e) => setTargetChannel(e.target.value)}
                    placeholder="e.g. @MKBHD or https://m.youtube.com/@MKBHD"
                    className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                  />
                </div>
              )}

              {ytStrategy === 'DIRECT_URL' && (
                <div className="pt-2 border-t border-neutral-900">
                  <label className="block text-[11px] text-neutral-400 mb-1 font-medium">Direct Target Video URL</label>
                  <input
                    type="text"
                    value={targetUrl}
                    onChange={(e) => setTargetUrl(e.target.value)}
                    placeholder="https://m.youtube.com/watch?v=..."
                    className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                  />
                </div>
              )}

              {/* Watch Retention Constraints (Applied unless Shorts Surfing) */}
              {ytStrategy !== 'SHORTS_SURF' && (
                <div className="pt-3 border-t border-neutral-900 grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-[11px] text-neutral-400 mb-1">Min Watch Duration (Seconds)</label>
                    <input
                      type="number"
                      value={minWatch}
                      onChange={(e) => setMinWatch(e.target.value)}
                      className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                    />
                  </div>
                  <div>
                    <label className="block text-[11px] text-neutral-400 mb-1">Max Watch Duration (Seconds)</label>
                    <input
                      type="number"
                      value={maxWatch}
                      onChange={(e) => setMaxWatch(e.target.value)}
                      className="w-full bg-neutral-900 border border-neutral-800 rounded-lg p-2 text-xs text-white"
                    />
                  </div>
                </div>
              )}

              {/* Behavioral & Engagement Matrix */}
              <div className="pt-3 border-t border-neutral-900 space-y-3">
                <span className="block text-[11px] font-semibold text-neutral-400">Human Engagement Matrix</span>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="flex justify-between text-[11px] text-neutral-400 mb-1">
                      <span className="flex items-center gap-1"><ThumbsUp className="w-3 h-3 text-blue-400" /> Like Probability</span>
                      <span className="text-white font-bold">{likeProb}%</span>
                    </div>
                    <input
                      type="range"
                      min="0"
                      max="100"
                      value={likeProb}
                      onChange={(e) => setLikeProb(e.target.value)}
                      className="w-full accent-blue-600 cursor-pointer"
                    />
                  </div>
                  <div>
                    <div className="flex justify-between text-[11px] text-neutral-400 mb-1">
                      <span className="flex items-center gap-1"><UserCheck className="w-3 h-3 text-red-400" /> Subscribe Probability</span>
                      <span className="text-white font-bold">{subProb}%</span>
                    </div>
                    <input
                      type="range"
                      min="0"
                      max="100"
                      value={subProb}
                      onChange={(e) => setSubProb(e.target.value)}
                      className="w-full accent-red-600 cursor-pointer"
                    />
                  </div>
                </div>

                <div className="flex flex-wrap gap-4 pt-2">
                  <label className="flex items-center gap-2 cursor-pointer text-xs text-neutral-300">
                    <input
                      type="checkbox"
                      checked={enableComments}
                      onChange={(e) => setEnableComments(e.target.checked)}
                      className="rounded bg-neutral-800 border-neutral-700 text-blue-600 cursor-pointer"
                    />
                    <MessageSquare className="w-3.5 h-3.5 text-purple-400" /> Dwell on Comments
                  </label>

                  <label className="flex items-center gap-2 cursor-pointer text-xs text-neutral-300">
                    <input
                      type="checkbox"
                      checked={enableScrubbing}
                      onChange={(e) => setEnableScrubbing(e.target.checked)}
                      className="rounded bg-neutral-800 border-neutral-700 text-blue-600 cursor-pointer"
                    />
                    <FastForward className="w-3.5 h-3.5 text-amber-400" /> Timeline Micro-Rewind
                  </label>

                  <label className="flex items-center gap-2 cursor-pointer text-xs text-neutral-300">
                    <input
                      type="checkbox"
                      checked={enableSpeedToggle}
                      onChange={(e) => setEnableSpeedToggle(e.target.checked)}
                      className="rounded bg-neutral-800 border-neutral-700 text-blue-600 cursor-pointer"
                    />
                    <Film className="w-3.5 h-3.5 text-emerald-400" /> Randomize 1.25x Speed
                  </label>
                </div>
              </div>
            </div>
          )}

          {/* Profile Selection Matrix */}
          <div>
            <div className="flex justify-between items-center mb-2">
              <label className="text-xs font-medium text-neutral-400">
                Assigned Profiles ({selectedProfileIds.length}/{profiles.length})
              </label>
              <button onClick={selectAllProfiles} className="text-xs text-blue-400 hover:underline cursor-pointer">
                {selectedProfileIds.length === profiles.length ? 'Deselect All' : 'Select All'}
              </button>
            </div>

            <div className="grid grid-cols-2 gap-2 max-h-36 overflow-y-auto pr-1">
              {profiles.map((p) => {
                const isSelected = selectedProfileIds.includes(p.id);
                return (
                  <div
                    key={p.id}
                    onClick={() => toggleSelectProfile(p.id)}
                    className={`p-2 rounded-lg border cursor-pointer flex items-center justify-between text-xs transition ${
                      isSelected
                        ? 'bg-blue-950/40 border-blue-500 text-white'
                        : 'bg-neutral-950 border-neutral-800 text-neutral-400 hover:border-neutral-700'
                    }`}
                  >
                    <span className="font-medium truncate">{p.name}</span>
                    <span className="text-[10px] text-neutral-500">{p.model_code || p.model_name}</span>
                  </div>
                );
              })}
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button onClick={onClose} className="px-4 py-2 text-xs text-neutral-400 hover:text-white cursor-pointer">
              Cancel
            </button>
            <button
              onClick={handleDispatch}
              className="flex items-center gap-1.5 bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold px-5 py-2.5 rounded-xl shadow-lg shadow-blue-600/20 cursor-pointer transition"
            >
              <Play className="w-3.5 h-3.5 fill-white" /> Compile & Dispatch DAGs
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
