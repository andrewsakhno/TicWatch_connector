package ticwatch

import (
	"context"
	"embed"
	"html/template"
	"io/fs"
	"net/http"
	"time"

	"cloud-server/internal/platform/httpx"
	"cloud-server/internal/platform/migrate"
)

const migrationsDir = "migrations"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// SampleQuery selects a page of stored samples.
type SampleQuery struct {
	Types           []int32
	From, To        int64
	AfterRecordedAt *int64
	AfterID         *int64
	Limit           int32
}

// RollupQuery selects one sensor type's buckets.
type RollupQuery struct {
	Type       int32
	Resolution RollupResolution
	From, To   int64
	Zone       string
}

// SampleStore keeps sample readings.
type SampleStore interface {
	Insert(ctx context.Context, deviceID string, samples []Sample) (int, error)
	Find(ctx context.Context, query SampleQuery) ([]StoredSample, error)
	Rollups(ctx context.Context, query RollupQuery) ([]RollupPoint, error)
}

// RoutePointStore keeps workout GPS fixes.
type RoutePointStore interface {
	Upsert(ctx context.Context, deviceID string, points []RoutePoint) (int, error)
	Find(ctx context.Context, from, to int64, limit int32) ([]RoutePoint, error)
}

// EcgRecordingStore keeps rhythm strips.
type EcgRecordingStore interface {
	Upsert(ctx context.Context, deviceID string, recordings []EcgRecording) (int, error)
}

// DeviationEpisodeStore keeps heart rate deviation episodes and their answers.
type DeviationEpisodeStore interface {
	Upsert(ctx context.Context, deviceID string, episodes []DeviationEpisode) (int, error)
}

// App is the TicWatch app of the platform.
type App struct {
	samples           SampleStore
	routePoints       RoutePointStore
	ecgRecordings     EcgRecordingStore
	deviationEpisodes DeviationEpisodeStore
	stats             HistoryStatsStore
	statsTemplate     *template.Template
	now               func() time.Time
}

// New wires the app to its stores.
func New(samples SampleStore, routePoints RoutePointStore, ecgRecordings EcgRecordingStore, deviationEpisodes DeviationEpisodeStore, stats HistoryStatsStore, now func() time.Time) *App {
	return &App{samples: samples, routePoints: routePoints, ecgRecordings: ecgRecordings, deviationEpisodes: deviationEpisodes,
		stats: stats, statsTemplate: newStatsPage(), now: now}
}

// Name implements app.App.
func (a *App) Name() string { return appName }

// Migrations implements app.App.
func (a *App) Migrations() migrate.Source {
	files, err := fs.Sub(migrationFiles, migrationsDir)
	if err != nil {
		panic(err) // migrationsDir is a compile-time embed of this package.
	}
	return migrate.Source{Schema: Schema, Files: files}
}

// PublicPaths implements app.App.
// The stats page needs no key either: the server is only reachable on the home LAN.
func (a *App) PublicPaths() []string { return []string{PingPath, StatsPath} }

// RegisterRoutes implements app.App.
func (a *App) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc(get(PingPath), httpx.Handle(a.ping))
	mux.HandleFunc(get(StatsPath), httpx.Handle(a.statsPage))
	mux.HandleFunc(post(SamplesPath), httpx.Handle(handleUpload(a.storeSamples)))
	mux.HandleFunc(get(SamplesPath), httpx.Handle(a.findSamples))
	mux.HandleFunc(get(RollupsPath), httpx.Handle(a.rollups))
	mux.HandleFunc(post(RoutePointsPath), httpx.Handle(handleUpload(a.storeRoutePoints)))
	mux.HandleFunc(get(RoutePointsPath), httpx.Handle(a.findRoutePoints))
	mux.HandleFunc(post(EcgRecordingsPath), httpx.Handle(handleUpload(a.storeEcgRecordings)))
	mux.HandleFunc(post(DeviationEpisodesPath), httpx.Handle(handleUpload(a.storeDeviationEpisodes)))
}

func get(path string) string  { return http.MethodGet + " " + path }
func post(path string) string { return http.MethodPost + " " + path }
