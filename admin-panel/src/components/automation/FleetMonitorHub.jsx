import React, { useState, useEffect } from 'react';
import {
  Smartphone,
  Server,
  Activity,
  Battery,
  BatteryCharging,
  BatteryWarning,
  Wifi,
  WifiOff,
  Power,
  RefreshCw,
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Clock,
  Layers,
  Cpu,
  Shield,
  Radio,
  ExternalLink,
  Sliders,
  Filter,
  Eye,
  X
} from 'lucide-react';
import {
  fetchFleetDevices,
  disableFleetDevice,
  enableFleetDevice,
  abortExecution
} from '../../api';

export default function FleetMonitorHub() {
  const [devices, setDevices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const [statusMsg, setStatusMsg] = useState(null);
  const [selectedDeviceDetails, setSelectedDeviceDetails] = useState(null);

  useEffect(() => {
    loadFleet();
    const interval = setInterval(loadFleetSilently, 5000);
    return () => clearInterval(interval);
  }, []);

  const showNotification = (text, type = 'success') => {
    setStatusMsg({ text, type });
    setTimeout(() => setStatusMsg(null), 4000);
  };

  const loadFleet = async () => {
    setLoading(true);
    try {
      const res = await fetchFleetDevices();
      setDevices(res.data || []);
    } catch (err) {
      console.error('Failed to load fleet devices:', err);
      showNotification('Failed to load fleet nodes', 'error');
    } finally {
      setLoading(false);
    }
  };

  const loadFleetSilently = async () => {
    try {
      const res = await fetchFleetDevices();
      setDevices(res.data || []);
    } catch (err) {
      // silent background failure
    }
  };

  const handleManualRefresh = async () => {
    setRefreshing(true);
    await loadFleet();
    setRefreshing(false);
  };

  const handleToggleDeviceState = async (device) => {
    const isCurrentlyDisabled = device.status === 'DISABLED';
    const actionName = isCurrentlyDisabled ? 'enable' : 'disable';
    if (!window.confirm(`Are you sure you want to ${actionName} device '${device.device_id}'?`)) return;

    try {
      if (isCurrentlyDisabled) {
        await enableFleetDevice(device.id);
        showNotification(`Device '${device.device_id}' enabled and online.`);
      } else {
        await disableFleetDevice(device.id);
        showNotification(`Device '${device.device_id}' disabled.`);
      }
      loadFleet();
    } catch (err) {
      showNotification(`Failed to ${actionName} device`, 'error');
    }
  };

  const handleAbortJob = async (device) => {
    if (!device.current_execution) return;
    const execId = device.current_execution;
    if (!window.confirm(`EMERGENCY: Abort active execution on device '${device.device_id}'?`)) return;

    try {
      await abortExecution(execId, 'Manually aborted from Fleet Monitor.');
      showNotification(`Execution on '${device.device_id}' aborted.`);
      loadFleet();
    } catch (err) {
      showNotification('Failed to abort execution', 'error');
    }
  };

  // Helper for battery styling
  const renderBatteryIcon = (percent) => {
    if (percent <= 20) {
      return (
        <span className="flex items-center gap-1 text-rose-400 font-mono text-xs">
          <BatteryWarning className="w-4 h-4 text-rose-400" />
          <span>{percent}%</span>
        </span>
      );
    }
    if (percent <= 50) {
      return (
        <span className="flex items-center gap-1 text-amber-300 font-mono text-xs">
          <Battery className="w-4 h-4 text-amber-300" />
          <span>{percent}%</span>
        </span>
      );
    }
    return (
      <span className="flex items-center gap-1 text-emerald-400 font-mono text-xs">
        <Battery className="w-4 h-4 text-emerald-400" />
        <span>{percent}%</span>
      </span>
    );
  };

  // Helper for status badge
  const renderStatusBadge = (status) => {
    switch (status) {
      case 'BUSY':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-amber-500/10 text-amber-300 border border-amber-500/30">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping" />
            BUSY (EXECUTING)
          </span>
        );
      case 'ONLINE':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/10 text-emerald-300 border border-emerald-500/30">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            ONLINE (READY)
          </span>
        );
      case 'DISABLED':
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-rose-500/10 text-rose-300 border border-rose-500/30">
            <Power className="w-3 h-3 text-rose-400" />
            DISABLED
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-neutral-800 text-neutral-400 border border-neutral-700">
            <WifiOff className="w-3 h-3 text-neutral-500" />
            OFFLINE
          </span>
        );
    }
  };

  // Filtered devices
  const filteredDevices = devices.filter((d) => {
    if (filter === 'ALL') return true;
    return d.status === filter;
  });

  const onlineCount = devices.filter((d) => d.status === 'ONLINE').length;
  const busyCount = devices.filter((d) => d.status === 'BUSY').length;
  const offlineCount = devices.filter((d) => d.status === 'OFFLINE').length;
  const disabledCount = devices.filter((d) => d.status === 'DISABLED').length;

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-neutral-400">
        <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin mb-3" />
        <p className="text-xs font-mono">Connecting to Distributed Node Registry...</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Toast Notification */}
      {statusMsg && (
        <div
          className={`fixed bottom-6 right-6 z-50 px-4 py-3 rounded-xl shadow-2xl border text-sm flex items-center gap-2 backdrop-blur-md transition-all ${
            statusMsg.type === 'error'
              ? 'bg-rose-950/90 text-rose-200 border-rose-800'
              : 'bg-emerald-950/90 text-emerald-200 border-emerald-800'
          }`}
        >
          {statusMsg.type === 'error' ? <AlertCircle className="w-4 h-4 text-rose-400" /> : <CheckCircle2 className="w-4 h-4 text-emerald-400" />}
          <span>{statusMsg.text}</span>
        </div>
      )}

      {/* Header Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0D111A] p-6 rounded-2xl border border-[#1E2638]">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-cyan-600 via-blue-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-cyan-500/20">
            <Radio className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-white tracking-tight">Fleet Monitor & Node Registry</h1>
            <p className="text-xs text-neutral-400">
              Live mobile device cluster, GeckoView runtime versions, battery levels, and active lease supervisors.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={handleManualRefresh}
            disabled={refreshing}
            className="px-3.5 py-2 rounded-xl bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 hover:text-white text-xs font-medium border border-[#1E2638] flex items-center gap-2 transition-colors"
            title="Poll device heartbeats"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin text-cyan-400' : ''}`} />
            <span>Poll Fleet</span>
          </button>
        </div>
      </div>

      {/* Overview Stat Cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div
          onClick={() => setFilter('ALL')}
          className={`p-4 rounded-xl border cursor-pointer transition-all ${
            filter === 'ALL'
              ? 'bg-blue-600/10 border-blue-500/40 shadow-lg shadow-blue-500/5'
              : 'bg-[#0D111A] border-[#1E2638] hover:border-neutral-700'
          }`}
        >
          <div className="flex items-center justify-between text-neutral-400 text-xs font-medium">
            <span>Total Nodes</span>
            <Server className="w-4 h-4 text-blue-400" />
          </div>
          <p className="text-2xl font-bold text-white font-mono mt-1">{devices.length}</p>
        </div>

        <div
          onClick={() => setFilter('ONLINE')}
          className={`p-4 rounded-xl border cursor-pointer transition-all ${
            filter === 'ONLINE'
              ? 'bg-emerald-600/10 border-emerald-500/40 shadow-lg shadow-emerald-500/5'
              : 'bg-[#0D111A] border-[#1E2638] hover:border-neutral-700'
          }`}
        >
          <div className="flex items-center justify-between text-neutral-400 text-xs font-medium">
            <span>Online & Ready</span>
            <Wifi className="w-4 h-4 text-emerald-400" />
          </div>
          <p className="text-2xl font-bold text-emerald-400 font-mono mt-1">{onlineCount}</p>
        </div>

        <div
          onClick={() => setFilter('BUSY')}
          className={`p-4 rounded-xl border cursor-pointer transition-all ${
            filter === 'BUSY'
              ? 'bg-amber-600/10 border-amber-500/40 shadow-lg shadow-amber-500/5'
              : 'bg-[#0D111A] border-[#1E2638] hover:border-neutral-700'
          }`}
        >
          <div className="flex items-center justify-between text-neutral-400 text-xs font-medium">
            <span>Executing Tasks</span>
            <Activity className="w-4 h-4 text-amber-400" />
          </div>
          <p className="text-2xl font-bold text-amber-300 font-mono mt-1">{busyCount}</p>
        </div>

        <div
          onClick={() => setFilter('OFFLINE')}
          className={`p-4 rounded-xl border cursor-pointer transition-all ${
            filter === 'OFFLINE'
              ? 'bg-neutral-800/40 border-neutral-600 shadow-lg'
              : 'bg-[#0D111A] border-[#1E2638] hover:border-neutral-700'
          }`}
        >
          <div className="flex items-center justify-between text-neutral-400 text-xs font-medium">
            <span>Offline / Stale</span>
            <WifiOff className="w-4 h-4 text-neutral-500" />
          </div>
          <p className="text-2xl font-bold text-neutral-400 font-mono mt-1">{offlineCount}</p>
        </div>
      </div>

      {/* Device Grid */}
      <div className="bg-[#0D111A] rounded-2xl border border-[#1E2638] overflow-hidden">
        <div className="p-4 border-b border-[#1E2638] flex items-center justify-between bg-[#0A0D14]/50">
          <div className="flex items-center gap-2">
            <Smartphone className="w-4 h-4 text-cyan-400" />
            <span className="text-xs font-bold uppercase tracking-wider text-neutral-300">
              Registered Hardware Nodes ({filteredDevices.length})
            </span>
          </div>

          {/* Quick Filter Pills */}
          <div className="flex items-center gap-1.5">
            {['ALL', 'ONLINE', 'BUSY', 'OFFLINE', 'DISABLED'].map((f) => (
              <button
                key={f}
                onClick={() => setFilter(f)}
                className={`px-2.5 py-1 rounded-lg text-[10px] font-semibold tracking-wide transition-colors ${
                  filter === f
                    ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40'
                    : 'text-neutral-400 hover:text-neutral-200 hover:bg-[#161B26]'
                }`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>

        {filteredDevices.length === 0 ? (
          <div className="py-16 text-center text-neutral-400 space-y-3">
            <Smartphone className="w-10 h-10 mx-auto text-neutral-600" />
            <p className="text-sm font-medium">No devices match filter '{filter}'.</p>
            <p className="text-xs text-neutral-500 max-w-sm mx-auto">
              Launch the Android TersoPilot app and start the Automation Worker Service to automatically register your phone into the fleet.
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 p-5">
            {filteredDevices.map((device) => {
              const nowSec = Date.now() / 1000;
              const lastHbSec = device.last_heartbeat ? new Date(device.last_heartbeat).getTime() / 1000 : null;
              const diffHb = lastHbSec ? Math.max(0, Math.round(nowSec - lastHbSec)) : null;

              return (
                <div
                  key={device.id}
                  className="bg-[#0A0D14] border border-[#1E2638] hover:border-neutral-700 rounded-xl p-5 flex flex-col justify-between gap-4 transition-all hover:shadow-xl"
                >
                  {/* Card Top */}
                  <div className="space-y-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                          <Smartphone className="w-4 h-4 text-cyan-400 shrink-0" />
                          <h3 className="font-bold text-sm text-white truncate" title={device.device_id}>
                            {device.brand ? `${device.brand} ${device.model_name}` : device.device_id}
                          </h3>
                        </div>
                        <p className="font-mono text-[10px] text-neutral-400 truncate pl-5.5">
                          ID: {device.device_id}
                        </p>
                      </div>
                      <div className="shrink-0">{renderStatusBadge(device.status)}</div>
                    </div>

                    {/* Specs Pills */}
                    <div className="flex flex-wrap items-center gap-2 pt-1 text-[11px] text-neutral-400">
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        Android {device.android_version}
                      </span>
                      {device.geckoview_version && (
                        <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638] text-neutral-300">
                          GeckoView {device.geckoview_version}
                        </span>
                      )}
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        {device.screen_width}x{device.screen_height}
                      </span>
                      <div className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        {renderBatteryIcon(device.battery_percent)}
                      </div>
                    </div>

                    {/* Heartbeat time */}
                    <div className="flex items-center gap-1 text-[11px] text-neutral-500">
                      <Clock className="w-3 h-3 text-neutral-400" />
                      <span>
                        Heartbeat:{' '}
                        <strong className="text-neutral-300 font-mono">
                          {diffHb !== null ? `${diffHb}s ago` : 'never'}
                        </strong>
                      </span>
                    </div>

                    {/* Active Execution section if busy */}
                    {device.current_execution && (
                      <div className="p-3 rounded-lg bg-amber-950/20 border border-amber-900/30 text-xs space-y-1">
                        <div className="flex items-center justify-between text-amber-300 font-semibold text-[11px]">
                          <span className="flex items-center gap-1">
                            <Activity className="w-3.5 h-3.5" />
                            <span>Active Job</span>
                          </span>
                          <span className="font-mono text-[10px] truncate max-w-[120px]">
                            {device.current_execution}
                          </span>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Card Bottom Controls */}
                  <div className="pt-3 border-t border-[#1E2638] flex items-center justify-between gap-2">
                    <button
                      onClick={() => setSelectedDeviceDetails(device)}
                      className="px-2.5 py-1.5 rounded-lg bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 hover:text-white text-xs font-medium flex items-center gap-1.5 transition-colors"
                      title="Inspect node telemetry"
                    >
                      <Eye className="w-3.5 h-3.5 text-cyan-400" />
                      <span>Telemetry</span>
                    </button>

                    <div className="flex items-center gap-2">
                      {device.current_execution && (
                        <button
                          onClick={() => handleAbortJob(device)}
                          className="px-2.5 py-1.5 rounded-lg bg-rose-600/20 hover:bg-rose-600/30 text-rose-300 border border-rose-500/30 text-xs font-semibold flex items-center gap-1 transition-colors"
                          title="Abort active execution on device"
                        >
                          <AlertOctagon className="w-3.5 h-3.5" />
                          <span>Abort</span>
                        </button>
                      )}

                      <button
                        onClick={() => handleToggleDeviceState(device)}
                        className={`p-1.5 rounded-lg border text-xs font-medium transition-colors ${
                          device.status === 'DISABLED'
                            ? 'bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border-emerald-500/30'
                            : 'bg-neutral-800 hover:bg-neutral-700 text-neutral-400 hover:text-rose-300 border-neutral-700'
                        }`}
                        title={device.status === 'DISABLED' ? 'Enable Node' : 'Disable Node'}
                      >
                        <Power className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Telemetry Inspection Modal */}
      {selectedDeviceDetails && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0D111A] border border-[#1E2638] rounded-2xl w-full max-w-xl shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
            <div className="p-5 border-b border-[#1E2638] flex items-center justify-between bg-[#0A0D14]">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-xl bg-cyan-600/20 border border-cyan-500/30 flex items-center justify-center">
                  <Smartphone className="w-4 h-4 text-cyan-400" />
                </div>
                <div>
                  <h3 className="font-bold text-sm text-white">
                    {selectedDeviceDetails.brand} {selectedDeviceDetails.model_name}
                  </h3>
                  <p className="font-mono text-xs text-neutral-400">Node ID: {selectedDeviceDetails.device_id}</p>
                </div>
              </div>
              <button
                onClick={() => setSelectedDeviceDetails(null)}
                className="p-1 rounded-lg text-neutral-400 hover:text-white hover:bg-[#161B26]"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-6 space-y-4 max-h-[70vh] overflow-y-auto">
              <div className="space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-neutral-400">Hardware & OS Profile</h4>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div className="p-2.5 rounded-lg bg-[#161B26] border border-[#1E2638]">
                    <span className="text-neutral-500 block">Platform</span>
                    <strong className="text-white">{selectedDeviceDetails.platform}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg bg-[#161B26] border border-[#1E2638]">
                    <span className="text-neutral-500 block">Android OS</span>
                    <strong className="text-white">API {selectedDeviceDetails.android_version}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg bg-[#161B26] border border-[#1E2638]">
                    <span className="text-neutral-500 block">GeckoView Version</span>
                    <strong className="text-white">{selectedDeviceDetails.geckoview_version || 'N/A'}</strong>
                  </div>
                  <div className="p-2.5 rounded-lg bg-[#161B26] border border-[#1E2638]">
                    <span className="text-neutral-500 block">Screen Viewport</span>
                    <strong className="text-white">{selectedDeviceDetails.screen_width} x {selectedDeviceDetails.screen_height}</strong>
                  </div>
                </div>
              </div>

              <div className="space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-neutral-400">Capability Flags</h4>
                <pre className="p-3 bg-[#0A0D14] border border-[#1E2638] rounded-xl text-[11px] font-mono text-cyan-300 overflow-x-auto">
                  {JSON.stringify(selectedDeviceDetails.capabilities || {}, null, 2)}
                </pre>
              </div>

              <div className="space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-neutral-400">Telemetry Metadata</h4>
                <pre className="p-3 bg-[#0A0D14] border border-[#1E2638] rounded-xl text-[11px] font-mono text-neutral-300 overflow-x-auto">
                  {JSON.stringify({
                    status: selectedDeviceDetails.status,
                    battery_percent: selectedDeviceDetails.battery_percent,
                    last_seen: selectedDeviceDetails.last_seen,
                    last_heartbeat: selectedDeviceDetails.last_heartbeat,
                    current_execution: selectedDeviceDetails.current_execution
                  }, null, 2)}
                </pre>
              </div>
            </div>

            <div className="p-4 border-t border-[#1E2638] bg-[#0A0D14] flex justify-end">
              <button
                onClick={() => setSelectedDeviceDetails(null)}
                className="px-4 py-1.5 rounded-lg bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 hover:text-white text-xs font-medium"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
