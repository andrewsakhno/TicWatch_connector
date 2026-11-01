// Package db opens the one PostgreSQL pool shared by all apps; each app keeps its tables in its own schema.
package db

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// maxConnections stays small: the host is a dual-threaded Atom on a 5400 rpm disk.
const maxConnections = 4

// Config says where the database is. A non-empty User/Password overrides what URL carries.
type Config struct {
	URL      string
	User     string
	Password string
}

// Open connects and verifies the connection.
func Open(ctx context.Context, config Config) (*pgxpool.Pool, error) {
	poolConfig, err := pgxpool.ParseConfig(config.URL)
	if err != nil {
		return nil, fmt.Errorf("parse database URL: %w", err)
	}
	poolConfig.MaxConns = maxConnections
	if config.User != "" {
		poolConfig.ConnConfig.User = config.User
	}
	if config.Password != "" {
		poolConfig.ConnConfig.Password = config.Password
	}
	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}
	return pool, nil
}
