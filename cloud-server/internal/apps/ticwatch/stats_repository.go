package ticwatch

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// statsTable names a table of the schema and the epoch-ms column that dates its rows.
type statsTable struct {
	name        string
	timeColumn  string
	hasReceived bool
}

const (
	columnRecordedAt = "recorded_at"
	columnStartedAt  = "started_at"
	noReceivedSQL    = "NULL::timestamptz"
	receivedSQL      = "MAX(received_at)"

	databaseSizeSQL = `SELECT pg_database_size(current_database())`

	sampleTypeStatsSQL = `SELECT type, COUNT(*), MIN(recorded_at), MAX(recorded_at), MAX(received_at)
		FROM ` + tableSensorSample + ` GROUP BY type ORDER BY COUNT(*) DESC, type`
)

var statsTables = []statsTable{
	{name: tableSensorSample, timeColumn: columnRecordedAt, hasReceived: true},
	{name: tableRoutePoint, timeColumn: columnRecordedAt},
	{name: tableEcgRecording, timeColumn: columnStartedAt, hasReceived: true},
	{name: tableDeviationEpisode, timeColumn: columnStartedAt, hasReceived: true},
}

func (t statsTable) query() string {
	received := noReceivedSQL
	if t.hasReceived {
		received = receivedSQL
	}
	return `SELECT COUNT(*), MIN(` + t.timeColumn + `), MAX(` + t.timeColumn + `), ` + received +
		`, pg_total_relation_size('` + t.name + `') FROM ` + t.name
}

// StatsRepository is the PostgreSQL HistoryStatsStore.
type StatsRepository struct {
	pool *pgxpool.Pool
}

// NewStatsRepository wires the repository to its pool.
func NewStatsRepository(pool *pgxpool.Pool) *StatsRepository {
	return &StatsRepository{pool: pool}
}

// Overview implements HistoryStatsStore.
func (r *StatsRepository) Overview(ctx context.Context) (HistoryOverview, error) {
	var overview HistoryOverview
	if err := r.pool.QueryRow(ctx, databaseSizeSQL).Scan(&overview.DatabaseBytes); err != nil {
		return overview, err
	}
	for _, table := range statsTables {
		stats := TableStats{Name: table.name}
		var received *time.Time
		if err := r.pool.QueryRow(ctx, table.query()).
			Scan(&stats.Rows, &stats.FirstAt, &stats.LastAt, &received, &stats.SizeBytes); err != nil {
			return overview, err
		}
		stats.LastReceived = received
		overview.Tables = append(overview.Tables, stats)
	}
	rows, err := r.pool.Query(ctx, sampleTypeStatsSQL)
	if err != nil {
		return overview, err
	}
	defer rows.Close()
	for rows.Next() {
		var stats SampleTypeStats
		if err := rows.Scan(&stats.Code, &stats.Rows, &stats.FirstAt, &stats.LastAt, &stats.LastReceived); err != nil {
			return overview, err
		}
		overview.SampleTypes = append(overview.SampleTypes, stats)
	}
	return overview, rows.Err()
}
