import React, { useState, useEffect, useRef } from 'react';
import {
  Package,
  Upload,
  Download,
  Trash2,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Search,
  ExternalLink,
  Code,
  Layers,
  Sparkles,
  Puzzle,
  ChevronRight,
  Shield,
  Activity,
  FileArchive,
  RefreshCw,
  Plus,
  Play,
  Info
} from 'lucide-react';
import {
  fetchAddons,
  uploadAddonZip,
  toggleAddonActive,
  uninstallAddon,
  exportAddonZipUrl,
  fetchAddonDynamicSteps,
  importAddonTemplate
} from '../../api';

export default function AddonsHub({ onNotification, onNavigateToWorkflow }) {
  const [addons, setAddons] = useState([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('ALL');
  
  // Modals
  const [isUploadOpen, setIsUploadOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const fileInputRef = useRef(null);

  // Inspector / Template Drawers
  const [inspectingAddon, setInspectingAddon] = useState(null);
  const [templateAddon, setTemplateAddon] = useState(null);
  const [importingTemplate, setImportingTemplate] = useState(null);
  const [uninstallConfirmAddon, setUninstallConfirmAddon] = useState(null);

  const loadData = async () => {
    try {
      setLoading(true);
      const res = await fetchAddons();
      setAddons(res.data || []);
    } catch (err) {
      console.error('Failed to load addons:', err);
      if (onNotification) onNotification('Failed to load workflow extensions', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleToggleActive = async (addon) => {
    try {
      const res = await toggleAddonActive(addon.id);
      setAddons((prev) =>
        prev.map((a) => (a.id === addon.id ? { ...a, is_active: res.data.is_active } : a))
      );
      if (onNotification) {
        onNotification(
          `Addon "${addon.name}" is now ${res.data.is_active ? 'active' : 'disabled'}`,
          'success'
        );
      }
    } catch (err) {
      console.error('Failed to toggle addon state:', err);
      if (onNotification) onNotification('Could not update addon state', 'error');
    }
  };

  const handleFileDrop = (e) => {
    e.preventDefault();
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      const file = e.dataTransfer.files[0];
      if (file.name.endsWith('.zip')) {
        setSelectedFile(file);
        setUploadError(null);
      } else {
        setUploadError('Only .zip addon packages are supported');
      }
    }
  };

  const handleFileSelect = (e) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      if (file.name.endsWith('.zip')) {
        setSelectedFile(file);
        setUploadError(null);
      } else {
        setUploadError('Only .zip addon packages are supported');
      }
    }
  };

  const handleUploadSubmit = async () => {
    if (!selectedFile) return;
    try {
      setUploading(true);
      setUploadError(null);
      const formData = new FormData();
      formData.append('package', selectedFile);

      await uploadAddonZip(formData);
      if (onNotification) onNotification('Addon installed and activated successfully!', 'success');
      setIsUploadOpen(false);
      setSelectedFile(null);
      await loadData();
    } catch (err) {
      console.error('Failed to upload addon:', err);
      const msg = err.response?.data?.error || err.message || 'Addon installation failed';
      setUploadError(msg);
    } finally {
      setUploading(false);
    }
  };

  const handleUninstall = async (addon) => {
    try {
      await uninstallAddon(addon.id);
      setAddons((prev) => prev.filter((a) => a.id !== addon.id));
      setUninstallConfirmAddon(null);
      if (onNotification) {
        onNotification(`Addon "${addon.name}" uninstalled completely with zero residual files`, 'success');
      }
    } catch (err) {
      console.error('Failed to uninstall addon:', err);
      if (onNotification) onNotification('Failed to uninstall addon', 'error');
    }
  };

  const handleImportTemplate = async (addon, template) => {
    try {
      setImportingTemplate(template.name);
      const res = await importAddonTemplate(addon.id, template.name);
      if (onNotification) {
        onNotification(`Template "${template.name}" imported to Visual Workflows!`, 'success');
      }
      setTemplateAddon(null);
      if (onNavigateToWorkflow && res.data?.workflow?.id) {
        onNavigateToWorkflow(res.data.workflow.id);
      }
    } catch (err) {
      console.error('Failed to import template:', err);
      if (onNotification) onNotification('Failed to import workflow template', 'error');
    } finally {
      setImportingTemplate(null);
    }
  };

  // Stats calculation
  const totalAddons = addons.length;
  const activeAddons = addons.filter((a) => a.is_active).length;
  const totalDynamicSteps = addons.reduce(
    (acc, a) => acc + (a.is_active ? (a.manifest?.steps?.length || 0) : 0),
    0
  );
  const totalTemplates = addons.reduce(
    (acc, a) => acc + (a.manifest?.templates?.length || 0),
    0
  );

  // Filter addons
  const filteredAddons = addons.filter((a) => {
    const matchesSearch =
      a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      a.slug.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (a.description && a.description.toLowerCase().includes(searchQuery.toLowerCase()));
    const matchesCat =
      categoryFilter === 'ALL' ||
      a.category.toUpperCase() === categoryFilter.toUpperCase();
    return matchesSearch && matchesCat;
  });

  return (
    <div className="space-y-6">
      {/* Top Banner & Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0D111A] border border-[#1E2638] rounded-2xl p-6 relative overflow-hidden">
        <div className="absolute top-0 right-0 w-96 h-96 bg-blue-600/5 rounded-full blur-3xl pointer-events-none" />
        <div>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-500 to-blue-500 flex items-center justify-center shadow-lg shadow-indigo-500/20">
              <Puzzle className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-xl font-bold text-white tracking-tight">Extensions & Addons Hub</h1>
                <span className="text-[10px] font-mono uppercase bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 px-2 py-0.5 rounded-full font-semibold">
                  Modular Architecture
                </span>
              </div>
              <p className="text-xs text-neutral-400 mt-0.5">
                Plug-and-play workflow packages, spatial steps, and automation templates without modifying core code.
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          <button
            onClick={loadData}
            className="p-2.5 rounded-xl bg-[#131824] border border-[#1E2638] text-neutral-400 hover:text-white hover:border-[#2E3850] transition-colors"
            title="Refresh Addons"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>

          <button
            onClick={() => setIsUploadOpen(true)}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white font-medium text-xs shadow-lg shadow-blue-500/25 transition-all"
          >
            <Upload className="w-4 h-4" />
            <span>Upload Addon (.zip)</span>
          </button>
        </div>
      </div>

      {/* Stats Counter Bar */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="bg-[#0D111A] border border-[#1E2638] rounded-xl p-4 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-wider text-neutral-400">Installed Addons</div>
            <div className="text-2xl font-bold text-white mt-1">{totalAddons}</div>
          </div>
          <div className="w-10 h-10 rounded-lg bg-blue-500/10 border border-blue-500/20 flex items-center justify-center">
            <Package className="w-5 h-5 text-blue-400" />
          </div>
        </div>

        <div className="bg-[#0D111A] border border-[#1E2638] rounded-xl p-4 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-wider text-neutral-400">Active Extensions</div>
            <div className="text-2xl font-bold text-emerald-400 mt-1">{activeAddons}</div>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5 text-emerald-400" />
          </div>
        </div>

        <div className="bg-[#0D111A] border border-[#1E2638] rounded-xl p-4 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-wider text-neutral-400">Dynamic Steps</div>
            <div className="text-2xl font-bold text-indigo-400 mt-1">{totalDynamicSteps}</div>
          </div>
          <div className="w-10 h-10 rounded-lg bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center">
            <Layers className="w-5 h-5 text-indigo-400" />
          </div>
        </div>

        <div className="bg-[#0D111A] border border-[#1E2638] rounded-xl p-4 flex items-center justify-between">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-wider text-neutral-400">Bundled Templates</div>
            <div className="text-2xl font-bold text-amber-400 mt-1">{totalTemplates}</div>
          </div>
          <div className="w-10 h-10 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center">
            <Sparkles className="w-5 h-5 text-amber-400" />
          </div>
        </div>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-[#0D111A] border border-[#1E2638] rounded-xl p-3">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 text-neutral-500 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search extensions by name or keyword..."
            className="w-full bg-[#131824] border border-[#1E2638] rounded-lg pl-9 pr-3 py-1.5 text-xs text-white placeholder-neutral-500 focus:outline-none focus:border-blue-500"
          />
        </div>

        <div className="flex items-center gap-1.5 self-start sm:self-auto overflow-x-auto w-full sm:w-auto pb-1 sm:pb-0">
          {['ALL', 'VIDEO_STREAMING', 'SOCIAL', 'SCRAPING', 'SYSTEM'].map((cat) => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1 rounded-lg text-[11px] font-medium transition-all ${
                categoryFilter === cat
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'text-neutral-400 hover:text-white bg-[#131824] border border-[#1E2638]'
              }`}
            >
              {cat === 'ALL' ? 'All Addons' : cat.replace('_', ' ')}
            </button>
          ))}
        </div>
      </div>

      {/* Addons List Grid */}
      {loading ? (
        <div className="p-12 text-center bg-[#0D111A] border border-[#1E2638] rounded-2xl">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
          <p className="text-xs text-neutral-400">Loading extensions & addon catalog...</p>
        </div>
      ) : filteredAddons.length === 0 ? (
        <div className="p-12 text-center bg-[#0D111A] border border-[#1E2638] rounded-2xl">
          <Puzzle className="w-12 h-12 text-neutral-600 mx-auto mb-3" />
          <h3 className="text-sm font-semibold text-white">No Addons Found</h3>
          <p className="text-xs text-neutral-400 mt-1 max-w-sm mx-auto">
            {searchQuery
              ? 'No extensions match your search criteria. Try a different query.'
              : 'Upload a packaged .zip extension to install modular workflow steps.'}
          </p>
          <button
            onClick={() => setIsUploadOpen(true)}
            className="mt-4 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium inline-flex items-center gap-2"
          >
            <Upload className="w-4 h-4" />
            <span>Upload Addon</span>
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {filteredAddons.map((addon) => {
            const stepCount = addon.manifest?.steps?.length || 0;
            const templateCount = addon.manifest?.templates?.length || 0;

            return (
              <div
                key={addon.id}
                className={`bg-[#0D111A] border rounded-2xl p-5 flex flex-col justify-between transition-all ${
                  addon.is_active
                    ? 'border-[#1E2638] hover:border-blue-500/40'
                    : 'border-neutral-800/60 opacity-75'
                }`}
              >
                <div>
                  {/* Top Bar: Icon, Name, Active Toggle */}
                  <div className="flex items-start justify-between gap-3 mb-3">
                    <div className="flex items-center gap-3">
                      <div
                        className={`w-11 h-11 rounded-xl flex items-center justify-center shrink-0 border ${
                          addon.is_active
                            ? 'bg-blue-500/10 border-blue-500/30 text-blue-400'
                            : 'bg-neutral-800 border-neutral-700 text-neutral-500'
                        }`}
                      >
                        <Puzzle className="w-6 h-6" />
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <h3 className="font-semibold text-sm text-white tracking-tight">{addon.name}</h3>
                        </div>
                        <div className="flex items-center gap-2 mt-0.5">
                          <span className="text-[10px] font-mono text-neutral-400">v{addon.version}</span>
                          <span className="text-[10px] text-neutral-600">•</span>
                          <span className="text-[10px] text-neutral-400">by {addon.author || 'Antidetect'}</span>
                        </div>
                      </div>
                    </div>

                    {/* Toggle Switch */}
                    <button
                      onClick={() => handleToggleActive(addon)}
                      className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${
                        addon.is_active ? 'bg-blue-600' : 'bg-neutral-700'
                      }`}
                      title={addon.is_active ? 'Click to disable' : 'Click to activate'}
                    >
                      <span
                        className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow-lg ring-0 transition duration-200 ease-in-out ${
                          addon.is_active ? 'translate-x-4' : 'translate-x-0'
                        }`}
                      />
                    </button>
                  </div>

                  {/* Description */}
                  <p className="text-xs text-neutral-400 line-clamp-2 mb-4 leading-relaxed">
                    {addon.description || 'No description provided for this extension.'}
                  </p>

                  {/* Badges */}
                  <div className="flex flex-wrap items-center gap-2 mb-4">
                    <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded bg-[#131824] border border-[#1E2638] text-neutral-300">
                      {addon.platform || 'UNIVERSAL'}
                    </span>
                    <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded bg-blue-500/10 border border-blue-500/20 text-blue-400">
                      {addon.category || 'WORKFLOW'}
                    </span>
                    {stepCount > 0 && (
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-indigo-500/10 border border-indigo-500/20 text-indigo-400">
                        {stepCount} dynamic {stepCount === 1 ? 'step' : 'steps'}
                      </span>
                    )}
                    {templateCount > 0 && (
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 border border-amber-500/20 text-amber-400">
                        {templateCount} {templateCount === 1 ? 'template' : 'templates'}
                      </span>
                    )}
                  </div>
                </div>

                {/* Footer Action Buttons */}
                <div className="pt-3 border-t border-[#1E2638] flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5">
                    {stepCount > 0 && (
                      <button
                        onClick={() => setInspectingAddon(addon)}
                        className="px-2.5 py-1.5 rounded-lg bg-[#131824] border border-[#1E2638] text-neutral-300 hover:text-white hover:border-[#2E3850] text-[11px] font-medium flex items-center gap-1.5 transition-colors"
                        title="View custom steps contributed by this addon"
                      >
                        <Layers className="w-3.5 h-3.5 text-indigo-400" />
                        <span>Steps</span>
                      </button>
                    )}

                    {templateCount > 0 && (
                      <button
                        onClick={() => setTemplateAddon(addon)}
                        className="px-2.5 py-1.5 rounded-lg bg-[#131824] border border-[#1E2638] text-neutral-300 hover:text-white hover:border-[#2E3850] text-[11px] font-medium flex items-center gap-1.5 transition-colors"
                        title="View & import ready workflows"
                      >
                        <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                        <span>Templates</span>
                      </button>
                    )}
                  </div>

                  <div className="flex items-center gap-1">
                    <a
                      href={exportAddonZipUrl(addon.id)}
                      download
                      className="p-1.5 rounded-lg text-neutral-400 hover:text-blue-400 hover:bg-blue-500/10 transition-colors"
                      title="Export Addon (.zip)"
                    >
                      <Download className="w-4 h-4" />
                    </a>

                    <button
                      onClick={() => setUninstallConfirmAddon(addon)}
                      className="p-1.5 rounded-lg text-neutral-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                      title="Uninstall Addon"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Upload Addon Modal */}
      {isUploadOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-[#0D111A] border border-[#1E2638] rounded-2xl w-full max-w-lg p-6 shadow-2xl relative">
            <div className="flex items-center justify-between pb-4 border-b border-[#1E2638] mb-4">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-blue-500/10 border border-blue-500/20 flex items-center justify-center text-blue-400">
                  <Upload className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-white">Upload Workflow Addon</h3>
                  <p className="text-[11px] text-neutral-400">Upload a packaged .zip extension</p>
                </div>
              </div>
              <button
                onClick={() => {
                  setIsUploadOpen(false);
                  setSelectedFile(null);
                  setUploadError(null);
                }}
                className="text-neutral-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            {uploadError && (
              <div className="mb-4 p-3 rounded-xl bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs flex items-start gap-2">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{uploadError}</span>
              </div>
            )}

            {/* Dropzone */}
            <div
              onDragOver={(e) => e.preventDefault()}
              onDrop={handleFileDrop}
              onClick={() => fileInputRef.current?.click()}
              className="border-2 border-dashed border-[#1E2638] hover:border-blue-500/50 rounded-2xl p-8 text-center cursor-pointer transition-all bg-[#131824]/50 hover:bg-[#131824]"
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".zip"
                onChange={handleFileSelect}
                className="hidden"
              />
              <FileArchive className="w-12 h-12 text-blue-400/80 mx-auto mb-3" />
              <p className="text-xs font-semibold text-white">
                {selectedFile ? selectedFile.name : 'Click to select or drag and drop .zip package'}
              </p>
              <p className="text-[11px] text-neutral-400 mt-1">
                Must contain <code className="text-blue-400 font-mono">manifest.json</code> in the zip archive
              </p>
              {selectedFile && (
                <div className="mt-3 inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-[11px] font-mono">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  <span>{(selectedFile.size / 1024).toFixed(1)} KB ready for install</span>
                </div>
              )}
            </div>

            <div className="flex items-center justify-end gap-3 mt-6 pt-4 border-t border-[#1E2638]">
              <button
                type="button"
                onClick={() => {
                  setIsUploadOpen(false);
                  setSelectedFile(null);
                  setUploadError(null);
                }}
                className="px-4 py-2 rounded-xl text-neutral-400 hover:text-white text-xs font-medium"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!selectedFile || uploading}
                onClick={handleUploadSubmit}
                className="px-5 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-medium inline-flex items-center gap-2 shadow-lg shadow-blue-500/20 transition-all"
              >
                {uploading ? (
                  <>
                    <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                    <span>Installing Addon...</span>
                  </>
                ) : (
                  <>
                    <Upload className="w-3.5 h-3.5" />
                    <span>Install & Activate</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Inspect Steps Modal */}
      {inspectingAddon && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-[#0D111A] border border-[#1E2638] rounded-2xl w-full max-w-2xl max-h-[85vh] flex flex-col p-6 shadow-2xl relative">
            <div className="flex items-center justify-between pb-4 border-b border-[#1E2638]">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400">
                  <Layers className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-white">
                    {inspectingAddon.name} — Contributed Steps
                  </h3>
                  <p className="text-[11px] text-neutral-400">
                    Custom action steps dynamically injected into Visual Workflow builder
                  </p>
                </div>
              </div>
              <button
                onClick={() => setInspectingAddon(null)}
                className="text-neutral-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="overflow-y-auto py-4 space-y-3 flex-1 pr-1">
              {(inspectingAddon.manifest?.steps || []).map((step, idx) => (
                <div
                  key={idx}
                  className="bg-[#131824] border border-[#1E2638] rounded-xl p-4 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-white">{step.title}</span>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-blue-500/10 border border-blue-500/20 text-blue-400">
                        {step.type}
                      </span>
                    </div>
                    <span className="text-[10px] font-mono text-neutral-500 uppercase">
                      {step.category || 'General'}
                    </span>
                  </div>

                  {step.description && (
                    <p className="text-xs text-neutral-400">{step.description}</p>
                  )}

                  {/* Fields info */}
                  {step.fields && step.fields.length > 0 && (
                    <div className="pt-2">
                      <div className="text-[10px] font-mono uppercase text-neutral-500 mb-1">
                        Configurable Parameters:
                      </div>
                      <div className="grid grid-cols-2 gap-2">
                        {step.fields.map((f, fIdx) => (
                          <div
                            key={fIdx}
                            className="bg-[#0D111A] border border-[#1E2638] rounded-lg p-2 text-[11px]"
                          >
                            <span className="text-neutral-300 font-medium">{f.label || f.name}</span>
                            <span className="text-neutral-500 ml-1">({f.type || 'text'})</span>
                            {f.description && (
                              <p className="text-[10px] text-neutral-400 mt-0.5">{f.description}</p>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Compiler mapping */}
                  {step.compiler && (
                    <div className="pt-1">
                      <span className="text-[10px] font-mono text-neutral-500">
                        Compiled Runner Command:{' '}
                      </span>
                      <code className="text-[11px] font-mono text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded">
                        {step.compiler.command}
                      </code>
                    </div>
                  )}
                </div>
              ))}
            </div>

            <div className="pt-4 border-t border-[#1E2638] flex justify-end">
              <button
                onClick={() => setInspectingAddon(null)}
                className="px-4 py-2 rounded-xl bg-[#131824] border border-[#1E2638] text-white text-xs font-medium hover:bg-[#1a2030]"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Prebuilt Templates Modal */}
      {templateAddon && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-[#0D111A] border border-[#1E2638] rounded-2xl w-full max-w-2xl max-h-[85vh] flex flex-col p-6 shadow-2xl relative">
            <div className="flex items-center justify-between pb-4 border-b border-[#1E2638]">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
                  <Sparkles className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-white">
                    {templateAddon.name} — Bundled Templates
                  </h3>
                  <p className="text-[11px] text-neutral-400">
                    Import ready-to-run workflows directly into your Visual Workflow builder
                  </p>
                </div>
              </div>
              <button
                onClick={() => setTemplateAddon(null)}
                className="text-neutral-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="overflow-y-auto py-4 space-y-3 flex-1 pr-1">
              {(templateAddon.manifest?.templates || []).map((tpl, idx) => (
                <div
                  key={idx}
                  className="bg-[#131824] border border-[#1E2638] rounded-xl p-4 flex items-center justify-between gap-4"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-semibold text-white">{tpl.name}</h4>
                      {tpl.platform && (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-blue-500/10 border border-blue-500/20 text-blue-400">
                          {tpl.platform}
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-neutral-400 max-w-md">{tpl.description}</p>
                    <div className="text-[11px] font-mono text-neutral-500">
                      Contains {tpl.nodes?.length || tpl.journeys?.[0]?.steps?.length || 0} sequential action steps
                    </div>
                  </div>

                  <button
                    onClick={() => handleImportTemplate(templateAddon, tpl)}
                    disabled={importingTemplate === tpl.name}
                    className="px-4 py-2 rounded-xl bg-amber-500/10 hover:bg-amber-500/20 border border-amber-500/30 text-amber-300 font-medium text-xs flex items-center gap-2 shrink-0 transition-all"
                  >
                    {importingTemplate === tpl.name ? (
                      <>
                        <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                        <span>Importing...</span>
                      </>
                    ) : (
                      <>
                        <Sparkles className="w-3.5 h-3.5" />
                        <span>1-Click Import</span>
                      </>
                    )}
                  </button>
                </div>
              ))}
            </div>

            <div className="pt-4 border-t border-[#1E2638] flex justify-end">
              <button
                onClick={() => setTemplateAddon(null)}
                className="px-4 py-2 rounded-xl bg-[#131824] border border-[#1E2638] text-white text-xs font-medium hover:bg-[#1a2030]"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Uninstall Confirmation Modal */}
      {uninstallConfirmAddon && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-[#0D111A] border border-rose-500/30 rounded-2xl w-full max-w-md p-6 shadow-2xl">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-xl bg-rose-500/10 border border-rose-500/20 flex items-center justify-center text-rose-400">
                <Trash2 className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-semibold text-sm text-white">Uninstall Addon?</h3>
                <p className="text-xs text-neutral-400">Completely remove extension package</p>
              </div>
            </div>

            <p className="text-xs text-neutral-300 mb-6 leading-relaxed">
              Are you sure you want to completely uninstall{' '}
              <strong className="text-white font-semibold">{uninstallConfirmAddon.name}</strong>?
              This will remove all contributed dynamic steps, templates, and storage files without residue.
            </p>

            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => setUninstallConfirmAddon(null)}
                className="px-4 py-2 rounded-xl text-neutral-400 hover:text-white text-xs font-medium"
              >
                Cancel
              </button>
              <button
                onClick={() => handleUninstall(uninstallConfirmAddon)}
                className="px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-medium shadow-lg shadow-rose-500/20 transition-all"
              >
                Yes, Uninstall Completely
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
