// Command cloud-server is the composition root of the home server: it wires the hosted apps onto the platform.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"cloud-server/internal/apps/myapplication"
	"cloud-server/internal/apps/ticwatch"
	"cloud-server/internal/platform/app"
	"cloud-server/internal/platform/auth"
	"cloud-server/internal/platform/db"
	"cloud-server/internal/platform/jobs"
)

const (
	envPort       = "TICWATCH_CLOUD_PORT"
	envDBURL      = "TICWATCH_DB_URL"
	envDBUser     = "TICWATCH_DB_USER"
	envDBPassword = "TICWATCH_DB_PASSWORD"
	envAPIKey     = "TICWATCH_CLOUD_API_KEY"

	envMyApplicationAdminPassword = "MYAPPLICATION_ADMIN_PASSWORD"

	defaultPort       = "8766"
	defaultDBURL      = "postgres://localhost:5432/ticwatch"
	defaultDBUser     = "ticwatch"
	defaultDBPassword = "ticwatch"

	loopbackHost = "127.0.0.1"
	urlScheme    = "http://"

	flagHealthcheck = "healthcheck"

	startupTimeout     = 2 * time.Minute
	healthcheckTimeout = 4 * time.Second
	readHeaderTimeout  = 10 * time.Second
	readTimeout        = 2 * time.Minute
	writeTimeout       = 5 * time.Minute
	idleTimeout        = 2 * time.Minute
	shutdownTimeout    = 20 * time.Second

	exitFailure = 1
)

type config struct {
	port   string
	db     db.Config
	apiKey string

	myApplicationAdminPassword string
}

func loadConfig() config {
	return config{
		port: envOr(envPort, defaultPort),
		db: db.Config{
			URL:      envOr(envDBURL, defaultDBURL),
			User:     envOr(envDBUser, defaultDBUser),
			Password: envOr(envDBPassword, defaultDBPassword),
		},
		apiKey: os.Getenv(envAPIKey),

		myApplicationAdminPassword: os.Getenv(envMyApplicationAdminPassword),
	}
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func main() {
	healthcheck := flag.Bool(flagHealthcheck, false, "GET this server's ping and exit 0 if it answers")
	flag.Parse()
	cfg := loadConfig()
	var err error
	if *healthcheck {
		err = checkHealth(cfg.port)
	} else {
		err = run(cfg)
	}
	if err != nil {
		slog.Error("exiting", "error", err)
		os.Exit(exitFailure)
	}
}

// checkHealth backs the container healthcheck: a scratch image has no curl.
func checkHealth(port string) error {
	client := http.Client{Timeout: healthcheckTimeout}
	response, err := client.Get(urlScheme + net.JoinHostPort(loopbackHost, port) + ticwatch.PingPath)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("ping answered %d", response.StatusCode)
	}
	return nil
}

func run(cfg config) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	startupCtx, cancelStartup := context.WithTimeout(ctx, startupTimeout)
	defer cancelStartup()
	pool, err := db.Open(startupCtx, cfg.db)
	if err != nil {
		return err
	}
	defer pool.Close()

	apps := []app.App{
		ticwatch.New(
			ticwatch.NewSampleRepository(pool),
			ticwatch.NewRoutePointRepository(pool),
			ticwatch.NewEcgRecordingRepository(pool),
			time.Now,
		),
		myapplication.New(
			myapplication.NewDeviceRepository(pool),
			myapplication.NewTopicRepository(pool),
			cfg.myApplicationAdminPassword,
			time.Now,
		),
	}
	if err := app.Migrate(startupCtx, pool, apps); err != nil {
		return err
	}

	scheduler := &jobs.Scheduler{}
	app.RegisterJobs(scheduler, apps)
	go scheduler.Run(ctx)

	server := &http.Server{
		Addr:              net.JoinHostPort("", cfg.port),
		Handler:           app.Handler(apps, auth.StaticKey(cfg.apiKey)),
		ReadHeaderTimeout: readHeaderTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		IdleTimeout:       idleTimeout,
	}
	return serve(ctx, server)
}

// serve runs server until ctx ends, then lets in-flight uploads finish.
func serve(ctx context.Context, server *http.Server) error {
	failed := make(chan error, 1)
	go func() { failed <- server.ListenAndServe() }()
	slog.Info("listening", "addr", server.Addr)
	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
