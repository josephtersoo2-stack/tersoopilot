import React, { useState, useEffect, useRef } from 'react';
import {
  Crosshair,
  Sparkles,
  Camera,
  Save,
  Download,
  Upload,
  Trash2,
  Plus,
  X,
  RotateCcw,
  Sliders,
  CheckCircle2,
  AlertCircle,
  Smartphone,
  Eye,
  Layers,
  Search,
  ExternalLink,
  ChevronRight,
  Target,
  Loader2,
  Check,
  RefreshCw
} from 'lucide-react';
import {
  fetchCalibration,
  updateCalibration,
  calibrateWithVLM,
  captureDeviceScreen,
  downloadCalibrationJson,
  flushCalibration,
  importCalibrationJson,
  saveSingleAnchor,
  deleteSingleAnchor,
  testAnchorTap,
  pullCalibrationFromDevice,
  pushCalibrationToDevice
} from '../../api';

export default function CalibrationHub({ onNotification }) {
  const [platform, setPlatform] = useState('YOUTUBE');
  const [anchors, setAnchors] = useState({});
  const [settings, setSettings] = useState({
    initial_scroll_count: 2,
    natural_scroll_delay_min: 1.5,
    natural_scroll_delay_max: 2.8,
    videos_per_batch: 10,
    max_batches_per_keyword: 2,
    overshoot_scroll_enabled: true
  });
  const [screenshotBase64, setScreenshotBase64] = useState('');
  const [selectedAnchorKey, setSelectedAnchorKey] = useState('SEARCH_BUTTON_HOME');
  const [searchFilter, setSearchFilter] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [calibrating, setCalibrating] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const [flushing, setFlushing] = useState(false);
  const [importing, setImporting] = useState(false);
  const [pullingFromDevice, setPullingFromDevice] = useState(false);
  const [showAddModal, setShowAddModal] = useState(false);
  const [newAnchorKey, setNewAnchorKey] = useState('');
  const [newAnchorLabel, setNewAnchorLabel] = useState('');
  const [connectedDevices, setConnectedDevices] = useState([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState('');
  const [testingAnchorKey, setTestingAnchorKey] = useState(null);
  const [savingAnchorKey, setSavingAnchorKey] = useState(null);
  const [lastTapResult, setLastTapResult] = useState(null);

  const imageContainerRef = useRef(null);
  const fileInputRef = useRef(null);

  useEffect(() => {
    loadCalibrationData();
  }, [platform]);

  const loadCalibrationData = async () => {
    setLoading(true);
    try {
      const res = await fetchCalibration(platform);
      setAnchors(res.data.anchors || {});
      if (res.data.settings) setSettings(res.data.settings);
      if (res.data.screenshot_base64) setScreenshotBase64(res.data.screenshot_base64);
      if (res.data.connected_devices) {
        setConnectedDevices(res.data.connected_devices);
        if (res.data.connected_devices.length > 0 && !selectedDeviceId) {
          setSelectedDeviceId(res.data.connected_devices[0]);
        }
      }
    } catch (err) {
      console.error('Failed to load calibration:', err);
      if (onNotification) onNotification('Failed to load interface calibration', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleTestTap = async (key, spot) => {
    if (spot.x === null || spot.x === undefined || spot.y === null || spot.y === undefined) {
      if (onNotification) onNotification('Anchor coordinates must be set before testing', 'warning');
      return;
    }
    setTestingAnchorKey(key);
    try {
      const res = await testAnchorTap({
        anchor_id: key,
        x: spot.x,
        y: spot.y,
        device_id: selectedDeviceId || undefined,
        platform
      });
      const phys = res.data.physical || {};
      setLastTapResult({
        anchor_id: key,
        physical: phys,
        device_id: res.data.device_id,
        screen_size: res.data.screen_size,
        status: 'SUCCESS'
      });
      if (onNotification) {
        onNotification(`🎯 Tapped ${key} at physical (${phys.x}, ${phys.y}) on device ${res.data.device_id}!`, 'success');
      }
    } catch (err) {
      console.error('Test tap error:', err);
      if (onNotification) {
        onNotification('Test tap failed: ' + (err.response?.data?.error || err.message), 'error');
      }
    } finally {
      setTestingAnchorKey(null);
    }
  };

  const handleSaveSingleAnchor = async (key, spot) => {
    setSavingAnchorKey(key);
    try {
      await saveSingleAnchor({
        platform,
        anchor_id: key,
        x: spot.x,
        y: spot.y,
        label: spot.label,
        description: spot.description,
        category: getCategory(key)
      });
      if (onNotification) {
        onNotification(`✓ Anchor "${key}" saved individually!`, 'success');
      }
    } catch (err) {
      console.error('Save single anchor error:', err);
      if (onNotification) {
        onNotification('Failed to save anchor: ' + (err.response?.data?.error || err.message), 'error');
      }
    } finally {
      setSavingAnchorKey(null);
    }
  };

  const handleDeleteAnchor = async (key) => {
    if (!window.confirm(`Delete spatial anchor "${key}" from registry?`)) return;
    try {
      await deleteSingleAnchor(key, platform);
      setAnchors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
      if (selectedAnchorKey === key) setSelectedAnchorKey(null);
      if (onNotification) onNotification(`Anchor "${key}" removed from registry.`, 'info');
    } catch (err) {
      console.error('Delete anchor error:', err);
      if (onNotification) onNotification('Failed to delete anchor: ' + (err.response?.data?.error || err.message), 'error');
    }
  };

  const handleCanvasClick = (e) => {
    if (!imageContainerRef.current || !selectedAnchorKey) return;
    const rect = imageContainerRef.current.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const clickY = e.clientY - rect.top;

    const normX = Math.round(Math.max(0, Math.min(1000, (clickX / rect.width) * 1000)));
    const normY = Math.round(Math.max(0, Math.min(1000, (clickY / rect.height) * 1000)));

    setAnchors((prev) => ({
      ...prev,
      [selectedAnchorKey]: {
        ...(prev[selectedAnchorKey] || {}),
        x: normX,
        y: normY,
        label: prev[selectedAnchorKey]?.label || selectedAnchorKey,
        confidence: 1.0
      }
    }));
  };

  const handleCoordinateChange = (key, axis, value) => {
    if (value === '' || value === null) {
      setAnchors((prev) => ({
        ...prev,
        [key]: {
          ...(prev[key] || {}),
          [axis]: null
        }
      }));
      return;
    }
    const intVal = parseInt(value, 10);
    if (isNaN(intVal)) return;
    const clamped = Math.max(0, Math.min(1000, intVal));

    setAnchors((prev) => ({
      ...prev,
      [key]: {
        ...(prev[key] || {}),
        [axis]: clamped
      }
    }));
  };

  const handleClearAnchor = (key) => {
    setAnchors((prev) => ({
      ...prev,
      [key]: {
        ...(prev[key] || {}),
        x: null,
        y: null,
        confidence: 0.0
      }
    }));
    if (onNotification) onNotification(`Cleared spot for ${key}`, 'info');
  };

  const handleFlushAll = async () => {
    if (!window.confirm('Are you sure you want to flush all anchor coordinates? All spots will be reset to empty so you can register them cleanly.')) {
      return;
    }
    setFlushing(true);
    try {
      const res = await flushCalibration(platform);
      setAnchors(res.data.anchors || {});
      if (onNotification) onNotification('All spatial anchors flushed! Everything is now empty.', 'success');
    } catch (err) {
      console.error('Flush calibration error:', err);
      // Fallback local flush
      setAnchors((prev) => {
        const reset = {};
        Object.keys(prev).forEach((k) => {
          reset[k] = { ...prev[k], x: null, y: null, confidence: 0.0 };
        });
        return reset;
      });
      if (onNotification) onNotification('Anchors flushed locally. Click Save to persist.', 'info');
    } finally {
      setFlushing(false);
    }
  };

  const handleSaveCalibration = async () => {
    setSaving(true);
    try {
      const res = await updateCalibration({
        platform,
        anchors,
        settings,
        screenshot_base64: screenshotBase64
      });
      const synced = res.data.synced_devices || [];
      if (synced.length > 0) {
        if (onNotification) onNotification(`✓ Calibration saved & synced to device(s): ${synced.join(', ')}!`, 'success');
      } else {
        if (onNotification) onNotification('Calibration & settings saved successfully!', 'success');
      }
    } catch (err) {
      console.error('Failed to save calibration:', err);
      if (onNotification) onNotification('Error saving calibration: ' + (err.response?.data?.error || err.message), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handlePullFromDevice = async () => {
    setPullingFromDevice(true);
    try {
      const res = await pullCalibrationFromDevice({ platform, device_id: selectedDeviceId || undefined });
      setAnchors(res.data.anchors || {});
      if (res.data.settings) setSettings(res.data.settings);
      if (onNotification) {
        onNotification(`✓ Pulled ${res.data.imported_count || Object.keys(res.data.anchors || {}).length} anchors from device ${res.data.source_device || ''}!`, 'success');
      }
    } catch (err) {
      console.error('Pull from device error:', err);
      if (onNotification) {
        onNotification('Pull from device failed: ' + (err.response?.data?.error || err.message), 'error');
      }
    } finally {
      setPullingFromDevice(false);
    }
  };

  const handleCalibrateAI = async () => {
    if (!screenshotBase64) {
      if (onNotification) onNotification('Please capture or provide a device screenshot first.', 'warning');
      return;
    }
    setCalibrating(true);
    try {
      const res = await calibrateWithVLM({
        platform,
        image_base64: screenshotBase64
      });
      setAnchors(res.data.anchors || {});
      if (onNotification) onNotification(`Vision AI calibrated ${res.data.detected_count || 'all'} spatial anchors!`, 'success');
    } catch (err) {
      console.error('VLM calibration error:', err);
      if (onNotification) onNotification('AI Calibration failed: ' + (err.response?.data?.error || err.message), 'error');
    } finally {
      setCalibrating(false);
    }
  };

  const handleCaptureScreen = async () => {
    setCapturing(true);
    try {
      const res = await captureDeviceScreen({ platform });
      if (res.data.screenshot_base64) {
        setScreenshotBase64(res.data.screenshot_base64);
        if (onNotification) onNotification('Live screenshot captured from physical device!', 'success');
      }
    } catch (err) {
      console.error('Capture screen error:', err);
      if (onNotification) onNotification('Failed to capture screen: ' + (err.response?.data?.error || err.message), 'error');
    } finally {
      setCapturing(false);
    }
  };

  const handleDownloadJson = async () => {
    try {
      const res = await downloadCalibrationJson(platform);
      const jsonStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(res.data, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute('href', jsonStr);
      downloadAnchor.setAttribute('download', 'youtube_spatial_anchors.json');
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
      if (onNotification) onNotification('Exported youtube_spatial_anchors.json', 'success');
    } catch (err) {
      console.error('Download json error:', err);
      if (onNotification) onNotification('Failed to download json: ' + err.message, 'error');
    }
  };

  const handleImportClick = () => {
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
      fileInputRef.current.click();
    }
  };

  const handleFileImport = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setImporting(true);
    const reader = new FileReader();
    reader.onload = async (event) => {
      try {
        const parsed = JSON.parse(event.target.result);
        const res = await importCalibrationJson(parsed, platform);
        setAnchors(res.data.anchors || {});
        if (res.data.settings) setSettings(res.data.settings);
        if (onNotification) {
          onNotification(`Imported ${res.data.imported_count || Object.keys(parsed.anchors || parsed).length} spatial anchors from JSON!`, 'success');
        }
      } catch (err) {
        console.error('Failed to import JSON:', err);
        if (onNotification) {
          onNotification('Failed to import JSON: ' + (err.response?.data?.error || err.message), 'error');
        }
      } finally {
        setImporting(false);
      }
    };
    reader.onerror = () => {
      setImporting(false);
      if (onNotification) onNotification('Failed to read file', 'error');
    };
    reader.readAsText(file);
  };

  const handleAddAnchor = (e) => {
    e?.preventDefault();
    const cleanKey = newAnchorKey.trim().toUpperCase().replace(/[^A-Z0-9_]/g, '_');
    if (!cleanKey) return;

    setAnchors((prev) => ({
      ...prev,
      [cleanKey]: {
        x: null,
        y: null,
        label: newAnchorLabel.trim() || cleanKey,
        description: 'Custom registered anchor',
        confidence: 0.0
      }
    }));
    setSelectedAnchorKey(cleanKey);
    setNewAnchorKey('');
    setNewAnchorLabel('');
    setShowAddModal(false);
    if (onNotification) onNotification(`Added anchor "${cleanKey}". Click on screen to set coordinates.`, 'info');
  };

  // Grouping categories
  const getCategory = (key) => {
    if (key.startsWith('SEARCH_')) return 'SEARCH';
    if (key.startsWith('NAV_') || key.startsWith('TAB_')) return 'NAVIGATION';
    if (key.includes('LIKE') || key.includes('SUBSCRIBE') || key.includes('COMMENTS') || key.includes('SHARE')) return 'ENGAGEMENT';
    if (key.startsWith('PLAYER_')) return 'PLAYER';
    return 'OTHER';
  };

  const filteredAnchorKeys = Object.keys(anchors).filter((key) => {
    const matchesSearch = key.toLowerCase().includes(searchFilter.toLowerCase()) ||
      (anchors[key]?.label || '').toLowerCase().includes(searchFilter.toLowerCase());
    const matchesCategory = categoryFilter === 'ALL' || getCategory(key) === categoryFilter;
    return matchesSearch && matchesCategory;
  });

  const calibratedCount = Object.values(anchors).filter(
    (s) => s && s.x !== null && s.x !== undefined && s.y !== null && s.y !== undefined
  ).length;
  const totalCount = Object.keys(anchors).length;

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6 select-none">
      {/* Hidden File Input for Import JSON */}
      <input
        type="file"
        ref={fileInputRef}
        accept=".json,application/json"
        onChange={handleFileImport}
        className="hidden"
      />

      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0F1420] border border-[#1E2638] rounded-2xl p-6 shadow-xl">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-tr from-cyan-600 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20">
            <Crosshair className="w-6 h-6 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-white flex items-center gap-2">
              YouTube Spatial Calibration Hub
              <span className="text-xs px-2.5 py-0.5 rounded-full font-medium bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                0..1000 Scale
              </span>
            </h1>
            <p className="text-sm text-slate-400 mt-0.5">
              Deterministic UI spot targeting, Vision LLM auto-calibration, and natural search settings.
            </p>
          </div>
        </div>

        {/* Action Buttons & Device Selector */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Connected Device Selector */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-[#141A28] border border-[#2B354C] text-xs">
            <Smartphone className={`w-3.5 h-3.5 ${connectedDevices.length > 0 ? 'text-emerald-400' : 'text-slate-500'}`} />
            <span className="text-slate-400 font-medium">Device:</span>
            {connectedDevices.length > 0 ? (
              <select
                value={selectedDeviceId}
                onChange={(e) => setSelectedDeviceId(e.target.value)}
                className="bg-transparent text-emerald-300 font-mono text-xs focus:outline-none cursor-pointer"
              >
                {connectedDevices.map((d) => (
                  <option key={d} value={d} className="bg-[#0F1420] text-white">
                    {d}
                  </option>
                ))}
              </select>
            ) : (
              <span className="text-slate-500 font-mono text-xs">None Attached</span>
            )}
          </div>

          {lastTapResult && (
            <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-[11px] text-cyan-300 font-mono animate-fadeIn">
              <Target className="w-3.5 h-3.5 text-cyan-400" />
              <span>Tap {lastTapResult.anchor_id}: ({lastTapResult.physical.x}, {lastTapResult.physical.y})</span>
            </div>
          )}

          <button
            onClick={handleCaptureScreen}
            disabled={capturing}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-slate-200 text-xs font-medium transition shadow-sm"
          >
            <Camera className={`w-3.5 h-3.5 text-blue-400 ${capturing ? 'animate-pulse' : ''}`} />
            {capturing ? 'Capturing...' : 'Capture Device'}
          </button>

          <button
            onClick={handleCalibrateAI}
            disabled={calibrating || !screenshotBase64}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white text-xs font-medium transition shadow-lg shadow-purple-500/20 disabled:opacity-50"
          >
            <Sparkles className={`w-3.5 h-3.5 ${calibrating ? 'animate-spin' : ''}`} />
            {calibrating ? 'Calibrating AI...' : 'Calibrate with AI'}
          </button>

          <button
            onClick={handleDownloadJson}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-slate-200 text-xs font-medium transition shadow-sm"
            title="Download youtube_spatial_anchors.json"
          >
            <Download className="w-3.5 h-3.5 text-emerald-400" />
            Export JSON
          </button>

          <button
            onClick={handleImportClick}
            disabled={importing}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-slate-200 text-xs font-medium transition shadow-sm"
            title="Upload custom or backup anchors JSON"
          >
            <Upload className={`w-3.5 h-3.5 text-amber-400 ${importing ? 'animate-bounce' : ''}`} />
            {importing ? 'Importing...' : 'Import JSON'}
          </button>

          <button
            onClick={handlePullFromDevice}
            disabled={pullingFromDevice}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-slate-200 text-xs font-medium transition shadow-sm"
            title="Pull active spatial anchors from connected device storage"
          >
            <RefreshCw className={`w-3.5 h-3.5 text-cyan-400 ${pullingFromDevice ? 'animate-spin' : ''}`} />
            {pullingFromDevice ? 'Pulling...' : 'Sync from Device'}
          </button>

          <button
            onClick={handleFlushAll}
            disabled={flushing}
            className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 text-rose-300 text-xs font-medium transition shadow-sm"
            title="Flush and clear all registered coordinates"
          >
            <Trash2 className={`w-3.5 h-3.5 text-rose-400 ${flushing ? 'animate-spin' : ''}`} />
            {flushing ? 'Flushing...' : 'Flush All'}
          </button>

          <button
            onClick={handleSaveCalibration}
            disabled={saving}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium transition shadow-lg shadow-blue-500/25"
          >
            <Save className={`w-3.5 h-3.5 ${saving ? 'animate-spin' : ''}`} />
            {saving ? 'Saving...' : 'Save & Sync Fleet'}
          </button>
        </div>
      </div>

      {/* Main Grid: Visual Viewport (Left) + Anchors & Settings (Right) */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left: Device Canvas Viewport */}
        <div className="lg:col-span-6 xl:col-span-5 bg-[#0F1420] border border-[#1E2638] rounded-2xl p-5 flex flex-col items-center shadow-xl">
          <div className="w-full flex items-center justify-between pb-3 border-b border-[#1E2638] mb-4">
            <div className="flex items-center gap-2">
              <Smartphone className="w-4 h-4 text-cyan-400" />
              <span className="text-xs font-semibold text-slate-200 uppercase tracking-wider">
                Viewport Preview ({platform})
              </span>
            </div>
            <div className="text-[11px] text-slate-400">
              Click on screen to place <span className="text-cyan-400 font-semibold">{selectedAnchorKey}</span>
            </div>
          </div>

          {/* Interactive Screen Frame */}
          <div
            ref={imageContainerRef}
            onClick={handleCanvasClick}
            className="relative w-full max-w-[340px] aspect-[9/19.5] bg-[#080B11] border-4 border-[#1E2638] rounded-[2.5rem] overflow-hidden shadow-2xl cursor-crosshair group flex items-center justify-center select-none"
          >
            {/* Top Phone Speaker / Notch */}
            <div className="absolute top-2 w-20 h-4 bg-[#141A28] rounded-full z-20 pointer-events-none" />

            {screenshotBase64 ? (
              <img
                src={screenshotBase64.startsWith('data:') ? screenshotBase64 : `data:image/jpeg;base64,${screenshotBase64}`}
                alt="Device Viewport"
                className="w-full h-full object-fill pointer-events-none select-none"
              />
            ) : (
              <div className="text-center p-6 space-y-3">
                <Smartphone className="w-12 h-12 text-slate-600 mx-auto" />
                <p className="text-xs text-slate-400">
                  No active screenshot captured yet.
                </p>
                <button
                  onClick={handleCaptureScreen}
                  disabled={capturing}
                  className="px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-[11px] font-medium transition"
                >
                  Capture from Phone
                </button>
              </div>
            )}

            {/* SVG Overlays for Calibrated Spots (ONLY rendered when non-null) */}
            {screenshotBase64 && (
              <svg className="absolute inset-0 w-full h-full pointer-events-none z-10">
                {Object.entries(anchors).map(([key, spot]) => {
                  if (!spot || spot.x === null || spot.x === undefined || spot.y === null || spot.y === undefined) {
                    return null;
                  }
                  const isSelected = key === selectedAnchorKey;
                  const posX = `${spot.x / 10}%`;
                  const posY = `${spot.y / 10}%`;

                  return (
                    <g key={key}>
                      {/* Pulse Circle for Selected */}
                      {isSelected && (
                        <circle
                          cx={posX}
                          cy={posY}
                          r="14"
                          className="fill-cyan-500/20 stroke-cyan-400 stroke-2 animate-ping"
                        />
                      )}
                      {/* Center Crosshair Target */}
                      <circle
                        cx={posX}
                        cy={posY}
                        r={isSelected ? '6' : '4'}
                        className={
                          isSelected
                            ? 'fill-cyan-400 stroke-white stroke-1.5'
                            : 'fill-emerald-400 stroke-[#0F1420] stroke-1 opacity-85'
                        }
                      />
                      {/* Text Label on selected */}
                      {isSelected && (
                        <text
                          x={posX}
                          y={posY}
                          dy="-10"
                          textAnchor="middle"
                          className="fill-white text-[10px] font-bold drop-shadow-[0_2px_4px_rgba(0,0,0,0.9)]"
                        >
                          {key} ({spot.x}, {spot.y})
                        </text>
                      )}
                    </g>
                  );
                })}
              </svg>
            )}
          </div>

          <div className="w-full mt-4 pt-3 border-t border-[#1E2638] flex items-center justify-between text-xs text-slate-400">
            <span>
              Anchors:{' '}
              <strong className={calibratedCount > 0 ? 'text-emerald-400' : 'text-slate-500'}>
                {calibratedCount}
              </strong>{' '}
              / {totalCount} registered
            </span>
            <span className="text-emerald-400 font-medium">Resolution Normalized (0..1000)</span>
          </div>
        </div>

        {/* Right: Anchor Inspector & Natural Search Settings */}
        <div className="lg:col-span-6 xl:col-span-7 space-y-6">
          {/* Natural Search & Scroll Parameters */}
          <div className="bg-[#0F1420] border border-[#1E2638] rounded-2xl p-5 shadow-xl">
            <h2 className="text-sm font-semibold text-white flex items-center gap-2 mb-4">
              <Sliders className="w-4 h-4 text-cyan-400" />
              Natural Search & Scroll Engine Settings
            </h2>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="bg-[#141A28] border border-[#1E2638] rounded-xl p-3.5">
                <label className="text-xs text-slate-400 block mb-1">
                  Pre-Scan Natural Scrolls
                </label>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    max="6"
                    value={settings.initial_scroll_count || 2}
                    onChange={(e) =>
                      setSettings({ ...settings, initial_scroll_count: parseInt(e.target.value, 10) || 1 })
                    }
                    className="w-full bg-[#0D111A] border border-[#2B354C] rounded-lg px-3 py-1.5 text-sm text-white focus:outline-none focus:border-cyan-500"
                  />
                  <span className="text-xs text-slate-400">swipes</span>
                </div>
                <p className="text-[11px] text-slate-500 mt-1">Human scrolling passes before scanning top feed</p>
              </div>

              <div className="bg-[#141A28] border border-[#1E2638] rounded-xl p-3.5">
                <label className="text-xs text-slate-400 block mb-1">
                  Videos per Batch
                </label>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="5"
                    max="30"
                    value={settings.videos_per_batch || 10}
                    onChange={(e) =>
                      setSettings({ ...settings, videos_per_batch: parseInt(e.target.value, 10) || 10 })
                    }
                    className="w-full bg-[#0D111A] border border-[#2B354C] rounded-lg px-3 py-1.5 text-sm text-white focus:outline-none focus:border-cyan-500"
                  />
                  <span className="text-xs text-slate-400">cards</span>
                </div>
                <p className="text-[11px] text-slate-500 mt-1">Incremental cards to scan when video not in view</p>
              </div>

              <div className="bg-[#141A28] border border-[#1E2638] rounded-xl p-3.5">
                <label className="text-xs text-slate-400 block mb-1">
                  Scroll Delay Range
                </label>
                <div className="flex items-center gap-1.5">
                  <input
                    type="number"
                    step="0.1"
                    min="0.5"
                    max="5.0"
                    value={settings.natural_scroll_delay_min || 1.5}
                    onChange={(e) =>
                      setSettings({ ...settings, natural_scroll_delay_min: parseFloat(e.target.value) || 1.0 })
                    }
                    className="w-16 bg-[#0D111A] border border-[#2B354C] rounded-lg px-2 py-1.5 text-sm text-white text-center focus:outline-none focus:border-cyan-500"
                  />
                  <span className="text-xs text-slate-400">to</span>
                  <input
                    type="number"
                    step="0.1"
                    min="0.5"
                    max="5.0"
                    value={settings.natural_scroll_delay_max || 2.8}
                    onChange={(e) =>
                      setSettings({ ...settings, natural_scroll_delay_max: parseFloat(e.target.value) || 2.5 })
                    }
                    className="w-16 bg-[#0D111A] border border-[#2B354C] rounded-lg px-2 py-1.5 text-sm text-white text-center focus:outline-none focus:border-cyan-500"
                  />
                  <span className="text-xs text-slate-400">sec</span>
                </div>
                <p className="text-[11px] text-slate-500 mt-1">Randomized dwell pause between downward swipes</p>
              </div>
            </div>

            {/* Keyword Cascade Summary */}
            <div className="mt-4 p-3 rounded-xl bg-[#0D111A] border border-[#1E2638] flex items-center justify-between text-xs">
              <span className="text-slate-400 font-medium">Search Cascade Order:</span>
              <div className="flex items-center gap-1.5 text-[11px] text-slate-300">
                <span className="px-2 py-0.5 rounded bg-blue-500/20 text-blue-300 font-semibold">Keyword 1</span>
                <ChevronRight className="w-3.5 h-3.5 text-slate-500" />
                <span className="px-2 py-0.5 rounded bg-slate-800 text-slate-300">Scroll +10</span>
                <ChevronRight className="w-3.5 h-3.5 text-slate-500" />
                <span className="px-2 py-0.5 rounded bg-purple-500/20 text-purple-300 font-semibold">Keyword 2</span>
                <ChevronRight className="w-3.5 h-3.5 text-slate-500" />
                <span className="px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 font-semibold">Full Video Title</span>
                <ChevronRight className="w-3.5 h-3.5 text-slate-500" />
                <span className="px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300">Watch URL</span>
              </div>
            </div>
          </div>

          {/* Spatial Anchor Inspector */}
          <div className="bg-[#0F1420] border border-[#1E2638] rounded-2xl p-5 shadow-xl flex flex-col h-[520px]">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-[#1E2638]">
              <div className="flex items-center gap-2">
                <Layers className="w-4 h-4 text-emerald-400" />
                <span className="text-sm font-semibold text-white">Spatial Anchor Registry</span>
                <span className="text-xs px-2 py-0.5 rounded-full bg-[#1A2234] text-slate-300 font-mono">
                  {calibratedCount}/{totalCount}
                </span>
              </div>

              {/* Filters & Add Anchor */}
              <div className="flex items-center gap-2">
                <div className="relative">
                  <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2.5 top-2.5" />
                  <input
                    type="text"
                    placeholder="Search anchors..."
                    value={searchFilter}
                    onChange={(e) => setSearchFilter(e.target.value)}
                    className="bg-[#141A28] border border-[#2B354C] rounded-lg pl-8 pr-3 py-1 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-cyan-500 w-32"
                  />
                </div>

                <select
                  value={categoryFilter}
                  onChange={(e) => setCategoryFilter(e.target.value)}
                  className="bg-[#141A28] border border-[#2B354C] rounded-lg px-2.5 py-1 text-xs text-slate-200 focus:outline-none focus:border-cyan-500"
                >
                  <option value="ALL">All</option>
                  <option value="SEARCH">Search</option>
                  <option value="NAVIGATION">Nav</option>
                  <option value="ENGAGEMENT">Interact</option>
                  <option value="PLAYER">Player</option>
                </select>

                <button
                  onClick={() => setShowAddModal(true)}
                  className="p-1.5 rounded-lg bg-[#141A28] hover:bg-[#1E2638] border border-[#2B354C] text-cyan-400 transition"
                  title="Add Custom Anchor"
                >
                  <Plus className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>

            {/* Custom Anchor Modal */}
            {showAddModal && (
              <div className="p-3 my-2 bg-[#141A28] border border-cyan-500/40 rounded-xl space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-cyan-400">Add New Anchor Key</span>
                  <button onClick={() => setShowAddModal(false)} className="text-slate-400 hover:text-white">
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <input
                    type="text"
                    placeholder="ANCHOR_KEY_NAME"
                    value={newAnchorKey}
                    onChange={(e) => setNewAnchorKey(e.target.value)}
                    className="bg-[#0D111A] border border-[#2B354C] rounded-lg px-2.5 py-1 text-xs text-white uppercase focus:outline-none focus:border-cyan-500"
                  />
                  <input
                    type="text"
                    placeholder="Readable Label"
                    value={newAnchorLabel}
                    onChange={(e) => setNewAnchorLabel(e.target.value)}
                    className="bg-[#0D111A] border border-[#2B354C] rounded-lg px-2.5 py-1 text-xs text-white focus:outline-none focus:border-cyan-500"
                  />
                </div>
                <div className="flex justify-end gap-2 pt-1">
                  <button
                    onClick={() => setShowAddModal(false)}
                    className="px-2.5 py-1 rounded-lg bg-slate-800 text-slate-400 text-xs"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleAddAnchor}
                    className="px-3 py-1 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-medium"
                  >
                    Register Key
                  </button>
                </div>
              </div>
            )}

            {/* Anchors List with Coordinates */}
            <div className="overflow-y-auto flex-1 divide-y divide-[#1E2638] pr-1 mt-2">
              {filteredAnchorKeys.map((key) => {
                const spot = anchors[key] || {};
                const isSelected = key === selectedAnchorKey;
                const isCalibrated = spot.x !== null && spot.x !== undefined && spot.y !== null && spot.y !== undefined;

                return (
                  <div
                    key={key}
                    onClick={() => setSelectedAnchorKey(key)}
                    className={`p-3 rounded-xl transition cursor-pointer flex flex-col sm:flex-row sm:items-center justify-between gap-3 ${
                      isSelected
                        ? 'bg-cyan-500/10 border border-cyan-500/30'
                        : 'hover:bg-[#141A28] border border-transparent'
                    }`}
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <span
                          className={`w-2 h-2 rounded-full ${
                            isSelected
                              ? 'bg-cyan-400 animate-ping'
                              : isCalibrated
                              ? 'bg-emerald-400'
                              : 'bg-slate-600 ring-1 ring-slate-500'
                          }`}
                        />
                        <span className="text-xs font-bold text-white font-mono">{key}</span>
                        {spot.source === 'addon' && (
                          <span className="text-[10px] px-1.5 py-0.2 rounded bg-purple-500/15 text-purple-300 border border-purple-500/30 font-medium">
                            Ext: {spot.addon_name || 'Addon'}
                          </span>
                        )}
                        {isCalibrated ? (
                          <span className="text-[10px] px-1.5 py-0.2 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-medium">
                            Set
                          </span>
                        ) : (
                          <span className="text-[10px] px-1.5 py-0.2 rounded bg-slate-800 text-slate-500">
                            Unset
                          </span>
                        )}
                      </div>
                      <p className="text-[11px] text-slate-400 mt-0.5">
                        {spot.label || spot.description || 'Target UI control'}
                      </p>
                    </div>

                    {/* Coordinate & Testing Controls */}
                    <div className="flex items-center gap-1.5 self-end sm:self-center" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center gap-1 bg-[#0D111A] border border-[#2B354C] rounded-lg px-2 py-1">
                        <span className="text-[10px] font-bold text-slate-400">X:</span>
                        <input
                          type="number"
                          min="0"
                          max="1000"
                          placeholder="---"
                          value={spot.x ?? ''}
                          onChange={(e) => handleCoordinateChange(key, 'x', e.target.value)}
                          className="w-12 bg-transparent text-xs text-white font-mono text-center focus:outline-none placeholder-slate-600"
                        />
                      </div>

                      <div className="flex items-center gap-1 bg-[#0D111A] border border-[#2B354C] rounded-lg px-2 py-1">
                        <span className="text-[10px] font-bold text-slate-400">Y:</span>
                        <input
                          type="number"
                          min="0"
                          max="1000"
                          placeholder="---"
                          value={spot.y ?? ''}
                          onChange={(e) => handleCoordinateChange(key, 'y', e.target.value)}
                          className="w-12 bg-transparent text-xs text-white font-mono text-center focus:outline-none placeholder-slate-600"
                        />
                      </div>

                      {/* 1. Live Test Tap Button */}
                      <button
                        onClick={() => handleTestTap(key, spot)}
                        disabled={!isCalibrated || testingAnchorKey === key}
                        className={`p-1.5 rounded-lg border transition flex items-center justify-center ${
                          testingAnchorKey === key
                            ? 'bg-amber-500/20 border-amber-500/50 text-amber-400 animate-pulse'
                            : isCalibrated
                            ? 'bg-cyan-500/10 hover:bg-cyan-500/20 border-cyan-500/30 text-cyan-400 hover:scale-105 active:scale-95'
                            : 'opacity-30 cursor-not-allowed bg-slate-800 border-slate-700 text-slate-500'
                        }`}
                        title={isCalibrated ? `🎯 Test tap ${key} live on connected phone` : 'Set coordinates first to test tap'}
                      >
                        {testingAnchorKey === key ? (
                          <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <Target className="w-3.5 h-3.5" />
                        )}
                      </button>

                      {/* 2. Atomic Save Single Anchor Button */}
                      <button
                        onClick={() => handleSaveSingleAnchor(key, spot)}
                        disabled={savingAnchorKey === key}
                        className="p-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 transition hover:scale-105 active:scale-95"
                        title={`💾 Save ${key} individually to database`}
                      >
                        {savingAnchorKey === key ? (
                          <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <Save className="w-3.5 h-3.5" />
                        )}
                      </button>

                      {/* 3. Clear Coordinates */}
                      {isCalibrated && (
                        <button
                          onClick={() => handleClearAnchor(key)}
                          className="p-1.5 rounded-lg hover:bg-rose-500/20 text-slate-500 hover:text-rose-400 transition"
                          title={`Clear coordinates for ${key}`}
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      )}

                      {/* 4. Delete Anchor */}
                      <button
                        onClick={() => handleDeleteAnchor(key)}
                        className="p-1.5 rounded-lg hover:bg-rose-500/20 text-slate-600 hover:text-rose-400 transition"
                        title={`Delete anchor ${key} from registry`}
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                );
              })}

              {filteredAnchorKeys.length === 0 && (
                <div className="text-center py-12 text-slate-500 text-xs">
                  No anchors matching criteria.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
