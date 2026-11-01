// Package app is the seam between the platform and the apps hosted on it.
package app

import (
	"context"
	"fmt"
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"

	"cloud-server/internal/platform/auth"
	"cloud-server/internal/platform/jobs"
	"cloud-server/internal/platform/migrate"
)

// App is one hosted application: its schema's migrations plus its HTTP routes.
type App interface {
	Name() string
	Migrations() migrate.Source
	RegisterRoutes(mux *http.ServeMux)
	// PublicPaths lists the paths that need no key, e.g. a discovery probe.
	PublicPaths() []string
}

// JobProvider is implemented by an app that has background jobs.
type JobProvider interface {
	Jobs() []jobs.Job
}

// Migrate applies every app's migrations.
func Migrate(ctx context.Context, pool *pgxpool.Pool, apps []App) error {
	for _, hosted := range apps {
		if err := migrate.Apply(ctx, pool, hosted.Migrations()); err != nil {
			return fmt.Errorf("app %s: %w", hosted.Name(), err)
		}
	}
	return nil
}

// Handler builds the HTTP handler of all apps behind the key check.
func Handler(apps []App, keys auth.KeySource) http.Handler {
	mux := http.NewServeMux()
	var publicPaths []string
	for _, hosted := range apps {
		hosted.RegisterRoutes(mux)
		publicPaths = append(publicPaths, hosted.PublicPaths()...)
	}
	return auth.Require(keys, publicPaths, mux)
}

// RegisterJobs hands the apps' jobs to the scheduler.
func RegisterJobs(scheduler *jobs.Scheduler, apps []App) {
	for _, hosted := range apps {
		provider, ok := hosted.(JobProvider)
		if !ok {
			continue
		}
		for _, job := range provider.Jobs() {
			scheduler.Register(job)
		}
	}
}
