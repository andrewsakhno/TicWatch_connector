// Package jobs is a tiny in-process scheduler for future nightly tasks. Nothing is registered yet.
package jobs

import (
	"context"
	"log/slog"
	"sync"
	"time"
)

// Job is one periodic task. It must be cheap and incremental (resume from a stored position): the host is a netbook,
// and heavy work belongs here only in small steps, never on a request path.
type Job interface {
	Name() string
	Interval() time.Duration
	Run(ctx context.Context) error
}

// Scheduler runs each registered Job on its own ticker.
type Scheduler struct {
	jobs []Job
}

// Register adds a job; call before Run.
func (s *Scheduler) Register(job Job) {
	s.jobs = append(s.jobs, job)
}

// Run blocks until ctx ends. A job's failure is logged and retried at its next tick.
func (s *Scheduler) Run(ctx context.Context) {
	var running sync.WaitGroup
	for _, job := range s.jobs {
		running.Go(func() { runPeriodically(ctx, job) })
	}
	running.Wait()
}

func runPeriodically(ctx context.Context, job Job) {
	ticker := time.NewTicker(job.Interval())
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := job.Run(ctx); err != nil {
				slog.Error("job failed", "job", job.Name(), "error", err)
			}
		}
	}
}
