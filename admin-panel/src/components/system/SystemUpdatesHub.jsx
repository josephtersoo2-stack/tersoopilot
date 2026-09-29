import React, { useState, useEffect, useRef } from 'react';
import { 
  ShieldCheck, 
  UploadCloud, 
  RotateCcw, 
  CheckCircle2, 
  AlertCircle, 
  FileCode, 
  Terminal, 
  Copy, 
  Check, 
  Clock, 
  Layers, 
  RefreshCw,
  ChevronDown,
  ChevronUp,
  FileText
} from 'lucide-react';
import { fetchSystemUpdates, uploadSystemPatch, rollbackSystemPatch } from '../../api';

export default function SystemUpdatesHub({ showNotification }) {
  const [updates, setUpdates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [selectedFile, setSelectedFile] = useState(null);
  const [copiedCmd, setCopiedCmd] = useState(false);
  const [expandedPatch, setExpandedPatch] = useState(null);
  const [rollbackModal, setRollbackModal] = useState(null);
  const [rollingBack, setRollingBack] = useState(false);
  const fileInputRef = useRef(null);

  useEffect(() => {
    loadUpdates();
  }, []);

  const loadUpdates = async () => {
    setLoading(true);
    try {
      const res = await fetchSystemUpdates();
      setUpdates(res.data.patches || []);
    } catch (err) {
      console.error('Failed to load system updates:', err);
      if (showNotification) showNotification('Failed to fetch installed patches', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleCopyCommand = (text) => {
    navigator.clipboard.writeText(text);
    setCopiedCmd(true);
    setTimeout(() => setCopiedCmd(false), 2500);
  };

  const handleFileDrop = (e) => {
    e.preventDefault();
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      const file = e.dataTransfer.files[0];
      if (file.name.endsWith('.zip')) {
        setSelectedFile(file);
      } else {
        if (showNotification) showNotification('Please upload a .zip or .patch.zip archive', 'error');
      }
    }
  };

  const handleFileSelect = (e) => {
    if (e.target.files && e.target.files[0]) {
      setSelectedFile(e.target.files[0]);
    }
  };

  const handleUploadSubmit = async (e) => {
    e.preventDefault();
    if (!selectedFile) return;

    setUploading(true);
    setUploadProgress(20);

    const formData = new FormData();
    formData.append('package', selectedFile);

    try {
      setUploadProgress(60);
      const res = await uploadSystemPatch(formData);
      setUploadProgress(100);
      if (showNotification) {
        showNotification(res.data.message || 'Patch applied successfully!', 'success');
      }
      setSelectedFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      await loadUpdates();
    } catch (err) {
      console.error('Failed to upload patch:', err);
      const errorMsg = err.response?.data?.error || err.message || 'Failed to apply update';
      if (showNotification) showNotification(errorMsg, 'error');
    } finally {
      setUploading(false);
      setTimeout(() => setUploadProgress(0), 1000);
    }
  };

  const executeRollback = async (patchId) => {
    setRollingBack(true);
    try {
      const res = await rollbackSystemPatch(patchId);
      if (showNotification) {
        showNotification(res.data.message || `Patch ${patchId} rolled back cleanly.`, 'success');
      }
      setRollbackModal(null);
      await loadUpdates();
    } catch (err) {
      console.error('Failed rollback:', err);
      const errorMsg = err.response?.data?.error || err.message || 'Rollback failed';
      if (showNotification) showNotification(errorMsg, 'error');
    } finally {
      setRollingBack(false);
    }
  };

  const activePatches = updates.filter(u => u.status === 'ACTIVE');

  return (
    <div className="space-y-6 sm:space-y-8 animate-fadeIn">
      {/* Header Banner */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-[#111625] border border-[#1E293B] p-4 sm:p-6 rounded-2xl shadow-xl">
        <div className="space-y-1">
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-emerald-500/10 border border-emerald-500/20 rounded-xl text-emerald-400">
              <ShieldCheck className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-xl font-bold text-white flex items-center gap-2">
                System Updates &amp; Hot-Patches
                <span className="text-xs px-2.5 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-mono">
                  WordPress Style
                </span>
              </h1>
              <p className="text-sm text-slate-400">
                Deploy modular hot-patches with automatic shadow snapshots, Namecheap Passenger reloads, and terminal undo.
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={loadUpdates}
            className="flex items-center gap-2 px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm border border-slate-700 transition"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>
      </div>

      {/* Terminal Command Quick-Reference Card */}
      <div className="bg-gradient-to-r from-slate-900 to-indigo-950/40 border border-indigo-500/20 p-5 rounded-2xl flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div className="flex items-start gap-3">
          <div className="p-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 shrink-0 mt-0.5">
            <Terminal className="w-5 h-5" />
          </div>
          <div>
            <div className="text-sm font-semibold text-white">Terminal Instant Undo Command</div>
            <div className="text-xs text-slate-400 mt-0.5">
              If an update breaks anything, SSH into your Namecheap cPanel/terminal and run this to undo without touching other files:
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 w-full md:w-auto">
          <code className="px-3.5 py-2 bg-black/60 border border-slate-700 rounded-xl font-mono text-xs text-emerald-400 select-all overflow-x-auto">
            python manage.py rollback_update
          </code>
          <button
            onClick={() => handleCopyCommand('python manage.py rollback_update')}
            className="p-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-xl text-slate-300 hover:text-white transition"
            title="Copy command"
          >
            {copiedCmd ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
          </button>
        </div>
      </div>

      {/* Upload Zone */}
      <div className="bg-[#111625] border border-[#1E293B] rounded-2xl p-6 shadow-xl space-y-4">
        <h2 className="text-base font-semibold text-white flex items-center gap-2">
          <UploadCloud className="w-5 h-5 text-indigo-400" />
          Upload &amp; Apply Hot-Patch Package
        </h2>

        <form onSubmit={handleUploadSubmit} className="space-y-4">
          <div
            onDragOver={(e) => e.preventDefault()}
            onDrop={handleFileDrop}
            onClick={() => fileInputRef.current?.click()}
            className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition flex flex-col items-center justify-center gap-3 ${
              selectedFile 
                ? 'border-emerald-500/40 bg-emerald-500/5' 
                : 'border-slate-700 hover:border-indigo-500/50 bg-slate-900/40 hover:bg-indigo-500/5'
            }`}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".zip"
              onChange={handleFileSelect}
              className="hidden"
            />
            <div className="w-12 h-12 rounded-2xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400">
              <UploadCloud className="w-6 h-6" />
            </div>

            {selectedFile ? (
              <div className="space-y-1">
                <div className="text-sm font-semibold text-emerald-400 flex items-center justify-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4" />
                  Selected: {selectedFile.name} ({(selectedFile.size / 1024).toFixed(1)} KB)
                </div>
                <div className="text-xs text-slate-400">Click or drop another file to replace</div>
              </div>
            ) : (
              <div className="space-y-1">
                <div className="text-sm font-medium text-slate-300">
                  Drag and drop your <span className="text-indigo-400 font-mono">.patch.zip</span> file here, or click to browse
                </div>
                <div className="text-xs text-slate-500">
                  Accepts standard zip archives containing a <code className="text-slate-400">patch.json</code> manifest
                </div>
              </div>
            )}
          </div>

          {uploading && (
            <div className="space-y-2">
              <div className="flex justify-between text-xs text-slate-400">
                <span>Deploying hot-patch and creating shadow snapshot...</span>
                <span>{uploadProgress}%</span>
              </div>
              <div className="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
                <div 
                  className="bg-indigo-500 h-2 transition-all duration-300 rounded-full"
                  style={{ width: `${uploadProgress}%` }}
                />
              </div>
            </div>
          )}

          <div className="flex justify-end gap-3">
            {selectedFile && (
              <button
                type="button"
                onClick={() => { setSelectedFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }}
                className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm font-medium transition"
              >
                Clear
              </button>
            )}
            <button
              type="submit"
              disabled={!selectedFile || uploading}
              className="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-semibold flex items-center gap-2 shadow-lg shadow-indigo-600/30 transition"
            >
              <UploadCloud className="w-4 h-4" />
              {uploading ? 'Deploying Hot-Patch...' : 'Deploy Update Now'}
            </button>
          </div>
        </form>
      </div>

      {/* Installed Updates History */}
      <div className="bg-[#111625] border border-[#1E293B] rounded-2xl overflow-hidden shadow-xl">
        <div className="p-6 border-b border-[#1E293B] flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-white flex items-center gap-2">
              <Layers className="w-5 h-5 text-indigo-400" />
              Installed Updates &amp; Ledger
            </h2>
            <p className="text-xs text-slate-400 mt-1">
              {activePatches.length} active patch(es) currently deployed
            </p>
          </div>
        </div>

        {loading ? (
          <div className="p-12 text-center text-slate-400 text-sm flex items-center justify-center gap-3">
            <RefreshCw className="w-5 h-5 animate-spin text-indigo-400" />
            Loading system update ledger...
          </div>
        ) : updates.length === 0 ? (
          <div className="p-12 text-center text-slate-500 text-sm">
            No system updates or hot-patches installed yet. Upload your first patch package above.
          </div>
        ) : (
          <div className="divide-y divide-[#1E293B]">
            {updates.map((patch) => {
              const isActive = patch.status === 'ACTIVE';
              const isExpanded = expandedPatch === patch.patch_id;

              return (
                <div key={patch.patch_id} className="p-5 hover:bg-slate-900/40 transition">
                  <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
                    <div className="space-y-1.5 flex-1">
                      <div className="flex items-center gap-2.5 flex-wrap">
                        <span className={`text-[11px] font-mono px-2 py-0.5 rounded font-bold border ${
                          isActive 
                            ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' 
                            : 'bg-slate-800 text-slate-400 border-slate-700'
                        }`}>
                          {patch.status}
                        </span>
                        <h3 className="font-semibold text-white text-base">
                          {patch.name}
                        </h3>
                        <span className="text-xs font-mono bg-slate-800 text-slate-400 px-2 py-0.5 rounded">
                          v{patch.version}
                        </span>
                      </div>

                      <div className="flex items-center gap-4 text-xs text-slate-400 flex-wrap">
                        <span className="font-mono text-slate-500">{patch.patch_id}</span>
                        <span>•</span>
                        <span className="flex items-center gap-1">
                          <Clock className="w-3.5 h-3.5" />
                          Applied: {new Date(patch.applied_at).toLocaleString()}
                        </span>
                        <span>•</span>
                        <span className="flex items-center gap-1">
                          <FileCode className="w-3.5 h-3.5" />
                          {patch.file_count || (patch.files_modified?.length || 0)} files affected
                        </span>
                      </div>

                      {patch.description && (
                        <p className="text-xs text-slate-400 pt-1">{patch.description}</p>
                      )}
                    </div>

                    <div className="flex items-center gap-2 shrink-0">
                      {patch.files_modified && patch.files_modified.length > 0 && (
                        <button
                          onClick={() => setExpandedPatch(isExpanded ? null : patch.patch_id)}
                          className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg text-xs text-slate-300 flex items-center gap-1.5 transition"
                        >
                          <FileText className="w-3.5 h-3.5" />
                          Files ({patch.files_modified.length})
                          {isExpanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                        </button>
                      )}

                      {isActive && (
                        <button
                          onClick={() => setRollbackModal(patch)}
                          className="px-3.5 py-1.5 bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 text-rose-300 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition"
                        >
                          <RotateCcw className="w-3.5 h-3.5" />
                          Undo Update
                        </button>
                      )}
                    </div>
                  </div>

                  {/* Expanded Modified Files List */}
                  {isExpanded && patch.files_modified && (
                    <div className="mt-4 p-4 bg-slate-950/60 border border-slate-800 rounded-xl space-y-2">
                      <div className="text-xs font-semibold text-slate-300">Modified/Created Files:</div>
                      <div className="max-h-40 overflow-y-auto space-y-1 font-mono text-xs text-slate-400">
                        {patch.files_modified.map((f, idx) => (
                          <div key={idx} className="flex items-center gap-2">
                            <span className="text-indigo-400">↳</span>
                            <span>{f}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Rollback Confirmation Modal */}
      {rollbackModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 animate-fadeIn">
          <div className="bg-[#111625] border border-rose-500/30 rounded-2xl max-w-lg w-full p-6 shadow-2xl space-y-5">
            <div className="flex items-start gap-3">
              <div className="p-3 bg-rose-500/10 border border-rose-500/20 rounded-xl text-rose-400 shrink-0">
                <RotateCcw className="w-6 h-6" />
              </div>
              <div className="space-y-1">
                <h3 className="text-lg font-bold text-white">Undo System Update?</h3>
                <p className="text-sm text-slate-400">
                  This will safely revert all files altered by <strong className="text-white">{rollbackModal.name}</strong> back to their exact original pre-patch state from the shadow snapshot vault.
                </p>
              </div>
            </div>

            <div className="bg-slate-900 border border-slate-800 rounded-xl p-3 space-y-1.5 text-xs">
              <div className="flex justify-between text-slate-400">
                <span>Patch ID:</span>
                <span className="font-mono text-white">{rollbackModal.patch_id}</span>
              </div>
              <div className="flex justify-between text-slate-400">
                <span>Version:</span>
                <span className="text-white">v{rollbackModal.version}</span>
              </div>
              <div className="flex justify-between text-slate-400">
                <span>Affected Files:</span>
                <span className="text-white">{rollbackModal.files_modified?.length || 0}</span>
              </div>
              <div className="flex justify-between text-slate-400">
                <span>Passenger Reload:</span>
                <span className="text-emerald-400 font-semibold">Automatic Zero-Downtime</span>
              </div>
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => setRollbackModal(null)}
                disabled={rollingBack}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl text-sm font-medium transition"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => executeRollback(rollbackModal.patch_id)}
                disabled={rollingBack}
                className="px-4 py-2 bg-rose-600 hover:bg-rose-500 disabled:opacity-50 text-white rounded-xl text-sm font-semibold flex items-center gap-2 shadow-lg shadow-rose-600/30 transition"
              >
                <RotateCcw className={`w-4 h-4 ${rollingBack ? 'animate-spin' : ''}`} />
                {rollingBack ? 'Rolling back...' : 'Confirm Rollback'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
