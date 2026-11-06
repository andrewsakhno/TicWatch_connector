package ticwatch

import (
	"context"
	"encoding/json"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	tableSensorSample     = Schema + ".sensor_sample"
	tableRoutePoint       = Schema + ".route_point"
	tableEcgRecording     = Schema + ".ecg_recording"
	tableDeviationEpisode = Schema + ".deviation_episode"

	insertSampleSQL = `INSERT INTO ` + tableSensorSample + ` (type, value, recorded_at, ended_at, source, snapshot, device_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT DO NOTHING`

	findSamplesSQL = `SELECT id, type, value, recorded_at, ended_at, source, snapshot FROM ` + tableSensorSample + `
		WHERE type = ANY(@types::int[]) AND recorded_at >= @from AND recorded_at < @to `

	sampleCursorClause = `AND (recorded_at > @afterRecordedAt OR (recorded_at = @afterRecordedAt AND id > @afterId)) `

	findSamplesOrderSQL = `ORDER BY recorded_at, id LIMIT @limit`

	rollupsTemplateSelect = `SELECT `
	rollupsTemplateRest   = ` AS bucket, MIN(value) AS min_value, MAX(value) AS max_value,
			SUM(value) AS sum_value, COUNT(*) AS sample_count
		FROM ` + tableSensorSample + ` WHERE type = @type::int AND recorded_at >= @from AND recorded_at < @to
		GROUP BY bucket ORDER BY bucket`

	upsertRoutePointSQL = `INSERT INTO ` + tableRoutePoint + ` (recorded_at, source, latitude, longitude, altitude_m, accuracy_m, device_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (recorded_at, source) DO UPDATE SET latitude = EXCLUDED.latitude,
			longitude = EXCLUDED.longitude, altitude_m = EXCLUDED.altitude_m, accuracy_m = EXCLUDED.accuracy_m`

	findRoutePointsSQL = `SELECT recorded_at, source, latitude, longitude, altitude_m, accuracy_m FROM ` + tableRoutePoint + `
		WHERE recorded_at >= @from AND recorded_at < @to ORDER BY recorded_at, source LIMIT @limit`

	upsertEcgRecordingSQL = `INSERT INTO ` + tableEcgRecording + ` (started_at, payload, device_id) VALUES ($1, CAST($2 AS JSONB), $3)
		ON CONFLICT (started_at) DO UPDATE SET payload = EXCLUDED.payload, received_at = now()`

	upsertDeviationEpisodeSQL = `INSERT INTO ` + tableDeviationEpisode + ` (uid, direction, state, started_at, ended_at, peak_bpm, reasons,
			detected_at, asked_at, answered_at, discarded_at, device_id, minor, intensity, threshold_bpm, baseline_bpm, excess_bpm_minutes, worn_since, steps_before, rmssd_before, sleep_score, fatigue_percent, last_medication_at)
		VALUES ($1, $2, $3, $4, $5, $6, CAST($7 AS JSONB), $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23)
		ON CONFLICT (uid) DO UPDATE SET direction = EXCLUDED.direction, state = EXCLUDED.state,
			started_at = EXCLUDED.started_at, ended_at = EXCLUDED.ended_at, peak_bpm = EXCLUDED.peak_bpm,
			reasons = EXCLUDED.reasons, detected_at = EXCLUDED.detected_at, asked_at = EXCLUDED.asked_at,
			answered_at = EXCLUDED.answered_at, discarded_at = EXCLUDED.discarded_at, minor = EXCLUDED.minor, intensity = EXCLUDED.intensity, threshold_bpm = EXCLUDED.threshold_bpm, baseline_bpm = EXCLUDED.baseline_bpm, excess_bpm_minutes = EXCLUDED.excess_bpm_minutes, worn_since = EXCLUDED.worn_since, steps_before = EXCLUDED.steps_before, rmssd_before = EXCLUDED.rmssd_before, sleep_score = EXCLUDED.sleep_score, fatigue_percent = EXCLUDED.fatigue_percent, last_medication_at = EXCLUDED.last_medication_at,
			received_at = now()`
)

// SampleRepository is the PostgreSQL SampleStore.
type SampleRepository struct {
	pool *pgxpool.Pool
}

// NewSampleRepository wires the repository to its pool.
func NewSampleRepository(pool *pgxpool.Pool) *SampleRepository {
	return &SampleRepository{pool: pool}
}

// Insert stores samples idempotently and returns how many were new.
func (r *SampleRepository) Insert(ctx context.Context, deviceID string, samples []Sample) (int, error) {
	rows := make([][]any, len(samples))
	for i, sample := range samples {
		rows[i] = []any{sample.Type, sample.Value, sample.RecordedAt, sample.EndedAt, sample.Source, sample.Snapshot, deviceID}
	}
	return execBatch(ctx, r.pool, insertSampleSQL, rows)
}

// Find returns up to query.Limit samples ascending by (recorded_at, id), past the cursor when both its parts are set.
func (r *SampleRepository) Find(ctx context.Context, query SampleQuery) ([]StoredSample, error) {
	if len(query.Types) == 0 {
		return []StoredSample{}, nil
	}
	sql := findSamplesSQL
	if query.AfterRecordedAt != nil && query.AfterID != nil {
		sql += sampleCursorClause
	}
	sql += findSamplesOrderSQL
	rows, err := r.pool.Query(ctx, sql, pgx.NamedArgs{
		"types":           query.Types,
		"from":            query.From,
		"to":              query.To,
		"afterRecordedAt": query.AfterRecordedAt,
		"afterId":         query.AfterID,
		"limit":           query.Limit,
	})
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (StoredSample, error) {
		var sample StoredSample
		err := row.Scan(&sample.ID, &sample.Type, &sample.Value, &sample.RecordedAt, &sample.EndedAt, &sample.Source, &sample.Snapshot)
		return sample, err
	})
}

// Rollups aggregates the raw rows of [from, to) into the query's buckets.
func (r *SampleRepository) Rollups(ctx context.Context, query RollupQuery) ([]RollupPoint, error) {
	sql := rollupsTemplateSelect + rollupBucketSQL[query.Resolution] + rollupsTemplateRest
	rows, err := r.pool.Query(ctx, sql, pgx.NamedArgs{
		"type": query.Type,
		"from": query.From,
		"to":   query.To,
		"zone": query.Zone,
	})
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (RollupPoint, error) {
		var point RollupPoint
		err := row.Scan(&point.BucketStart, &point.Min, &point.Max, &point.Sum, &point.Count)
		return point, err
	})
}

// RoutePointRepository is the PostgreSQL RoutePointStore; a re-sent fix (same time + source) replaces itself.
type RoutePointRepository struct {
	pool *pgxpool.Pool
}

// NewRoutePointRepository wires the repository to its pool.
func NewRoutePointRepository(pool *pgxpool.Pool) *RoutePointRepository {
	return &RoutePointRepository{pool: pool}
}

// Upsert stores fixes and returns the affected row count.
func (r *RoutePointRepository) Upsert(ctx context.Context, deviceID string, points []RoutePoint) (int, error) {
	rows := make([][]any, len(points))
	for i, point := range points {
		rows[i] = []any{point.RecordedAt, point.Source, point.Latitude, point.Longitude, point.AltitudeMeters, point.HorizontalAccuracyMeters, deviceID}
	}
	return execBatch(ctx, r.pool, upsertRoutePointSQL, rows)
}

// Find returns up to limit fixes recorded in [from, to), ascending by (recorded_at, source).
func (r *RoutePointRepository) Find(ctx context.Context, from, to int64, limit int32) ([]RoutePoint, error) {
	rows, err := r.pool.Query(ctx, findRoutePointsSQL, pgx.NamedArgs{"from": from, "to": to, "limit": limit})
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (RoutePoint, error) {
		var point RoutePoint
		err := row.Scan(&point.RecordedAt, &point.Source, &point.Latitude, &point.Longitude, &point.AltitudeMeters, &point.HorizontalAccuracyMeters)
		return point, err
	})
}

// EcgRecordingRepository is the PostgreSQL EcgRecordingStore, keyed by start time.
type EcgRecordingRepository struct {
	pool *pgxpool.Pool
}

// NewEcgRecordingRepository wires the repository to its pool.
func NewEcgRecordingRepository(pool *pgxpool.Pool) *EcgRecordingRepository {
	return &EcgRecordingRepository{pool: pool}
}

// Upsert stores strips and returns the affected row count.
func (r *EcgRecordingRepository) Upsert(ctx context.Context, deviceID string, recordings []EcgRecording) (int, error) {
	rows := make([][]any, len(recordings))
	for i, recording := range recordings {
		rows[i] = []any{recording.StartedAt, string(recording.Payload), deviceID}
	}
	return execBatch(ctx, r.pool, upsertEcgRecordingSQL, rows)
}

// DeviationEpisodeRepository is the PostgreSQL DeviationEpisodeStore, keyed by the phone's uid.
type DeviationEpisodeRepository struct {
	pool *pgxpool.Pool
}

// NewDeviationEpisodeRepository wires the repository to its pool.
func NewDeviationEpisodeRepository(pool *pgxpool.Pool) *DeviationEpisodeRepository {
	return &DeviationEpisodeRepository{pool: pool}
}

// Upsert stores episodes and returns the affected row count.
func (r *DeviationEpisodeRepository) Upsert(ctx context.Context, deviceID string, episodes []DeviationEpisode) (int, error) {
	rows := make([][]any, len(episodes))
	for i, episode := range episodes {
		reasons, err := json.Marshal(episode.Reasons)
		if err != nil {
			return 0, err
		}
		rows[i] = []any{
			episode.UID, episode.Direction, episode.State, episode.StartedAt, episode.EndedAt, episode.PeakBpm, string(reasons),
			episode.DetectedAt, episode.AskedAt, episode.AnsweredAt, episode.DiscardedAt, deviceID,
			episode.Minor, episode.Intensity, episode.ThresholdBpm, episode.BaselineBpm, episode.ExcessBpmMinutes, episode.WornSince,
			episode.StepsBefore, episode.RmssdBefore, episode.SleepScore, episode.FatiguePercent, episode.LastMedicationAt,
		}
	}
	return execBatch(ctx, r.pool, upsertDeviationEpisodeSQL, rows)
}

// execBatch runs sql once per row in one transaction and sums the affected rows.
func execBatch(ctx context.Context, pool *pgxpool.Pool, sql string, rows [][]any) (int, error) {
	if len(rows) == 0 {
		return 0, nil
	}
	batch := &pgx.Batch{}
	for _, row := range rows {
		batch.Queue(sql, row...)
	}
	affected := 0
	err := pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		results := tx.SendBatch(ctx, batch)
		defer results.Close()
		for range rows {
			tag, err := results.Exec()
			if err != nil {
				return err
			}
			affected += int(tag.RowsAffected())
		}
		return results.Close()
	})
	if err != nil {
		return 0, err
	}
	return affected, nil
}
