// Package migrate applies an app's embedded SQL files to its own schema, each file once, in a transaction.
package migrate

import (
	"context"
	"fmt"
	"io/fs"
	"regexp"
	"sort"
	"strconv"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	trackingTable       = "schema_migrations"
	versionNumberBase   = 10
	versionNumberBits   = 64
	fileNameVersionPart = 1
)

// fileNamePattern is the Flyway naming the first migration was written with: V<number>__<description>.sql.
var fileNamePattern = regexp.MustCompile(`^V([0-9]+)__.+\.sql$`)

// Source is one app's migrations: Files holds them at its root and they run with Schema as the search path, so the
// SQL itself names no schema.
type Source struct {
	Schema string
	Files  fs.FS
}

// Apply creates the schema and its tracking table if needed, then runs the files not applied yet in version order.
func Apply(ctx context.Context, pool *pgxpool.Pool, source Source) error {
	schema := pgx.Identifier{source.Schema}.Sanitize()
	tracking := pgx.Identifier{source.Schema, trackingTable}.Sanitize()
	bootstrap := fmt.Sprintf(`CREATE SCHEMA IF NOT EXISTS %s;
		CREATE TABLE IF NOT EXISTS %s (
			file_name  TEXT PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`, schema, tracking)
	if _, err := pool.Exec(ctx, bootstrap); err != nil {
		return fmt.Errorf("prepare schema %s: %w", source.Schema, err)
	}
	files, err := orderedFiles(source.Files)
	if err != nil {
		return err
	}
	for _, name := range files {
		if err := applyFile(ctx, pool, source, tracking, name); err != nil {
			return fmt.Errorf("migration %s/%s: %w", source.Schema, name, err)
		}
	}
	return nil
}

func applyFile(ctx context.Context, pool *pgxpool.Pool, source Source, tracking, name string) error {
	return pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		var applied bool
		exists := fmt.Sprintf("SELECT EXISTS (SELECT 1 FROM %s WHERE file_name = $1)", tracking)
		if err := tx.QueryRow(ctx, exists, name).Scan(&applied); err != nil {
			return err
		}
		if applied {
			return nil
		}
		script, err := fs.ReadFile(source.Files, name)
		if err != nil {
			return err
		}
		searchPath := "SET LOCAL search_path TO " + pgx.Identifier{source.Schema}.Sanitize()
		if _, err := tx.Exec(ctx, searchPath); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(script)); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, fmt.Sprintf("INSERT INTO %s (file_name) VALUES ($1)", tracking), name)
		return err
	})
}

// orderedFiles sorts by version number, so V10 follows V9.
func orderedFiles(files fs.FS) ([]string, error) {
	entries, err := fs.ReadDir(files, ".")
	if err != nil {
		return nil, err
	}
	versions := make(map[string]uint64, len(entries))
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		match := fileNamePattern.FindStringSubmatch(entry.Name())
		if match == nil {
			return nil, fmt.Errorf("unexpected migration file name %q", entry.Name())
		}
		version, err := strconv.ParseUint(match[fileNameVersionPart], versionNumberBase, versionNumberBits)
		if err != nil {
			return nil, fmt.Errorf("migration file %q: %w", entry.Name(), err)
		}
		versions[entry.Name()] = version
		names = append(names, entry.Name())
	}
	sort.Slice(names, func(i, j int) bool { return versions[names[i]] < versions[names[j]] })
	return names, nil
}
