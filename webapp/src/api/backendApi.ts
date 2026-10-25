// Mirrors the SyncRequest/SyncResponse DTOs in
// phone-app/.../network/BackendApi.kt and backend's future sync controller.
export interface SensorSampleDto {
  type: 'HEART_RATE' | 'STEPS' | 'CALORIES';
  value: number;
  recordedAtEpochMs: number;
}

export interface SyncStatus {
  deviceId: string;
  lastSyncedAt: string | null;
  sampleCount: number;
}

const BACKEND_BASE_URL = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080';

export async function fetchSyncStatus(): Promise<SyncStatus[]> {
  const response = await fetch(`${BACKEND_BASE_URL}/api/v1/sync/status`);
  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`);
  }
  return response.json();
}
