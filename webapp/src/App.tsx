import { useEffect, useState } from 'react';
import './App.css';
import { fetchSyncStatus, type SyncStatus } from './api/backendApi';

function App() {
  const [devices, setDevices] = useState<SyncStatus[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSyncStatus()
      .then(setDevices)
      .catch((err: Error) => setError(err.message));
  }, []);

  return (
    <main>
      <h1>TicWatch Connector</h1>
      {error && <p role="alert">Backend unreachable: {error}</p>}
      {!error && devices.length === 0 && <p>No synced devices yet.</p>}
      <ul>
        {devices.map((device) => (
          <li key={device.deviceId}>
            {device.deviceId} — {device.sampleCount} samples — last sync:{' '}
            {device.lastSyncedAt ?? 'never'}
          </li>
        ))}
      </ul>
    </main>
  );
}

export default App;
