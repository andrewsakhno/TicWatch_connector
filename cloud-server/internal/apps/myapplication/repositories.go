package myapplication

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	tableDevice        = Schema + ".device"
	tableAccount       = Schema + ".account"
	tableTopicState    = Schema + ".topic_state"
	tableTopicBlob     = Schema + ".topic_blob"
	tableParentAccount = Schema + ".parent_account"

	deviceColumns = `device_id, name, role, account_id, first_seen_at, last_seen_at,
		(SELECT COALESCE(array_agg(link.account_id ORDER BY link.account_id), '{}') FROM ` + tableParentAccount + ` link
			WHERE link.device_id = device.device_id)`

	// A name is kept when the phone sends none, so an admin-recognized row doesn't go blank.
	touchDeviceSQL = `INSERT INTO ` + tableDevice + ` (device_id, name, role, first_seen_at, last_seen_at)
		VALUES ($1, $2, '` + string(RoleUnassigned) + `', $3, $4)
		ON CONFLICT (device_id) DO UPDATE SET
			name = CASE WHEN EXCLUDED.name = '' THEN ` + tableDevice + `.name ELSE EXCLUDED.name END,
			last_seen_at = EXCLUDED.last_seen_at
		RETURNING ` + deviceColumns

	findDeviceSQL = `SELECT ` + deviceColumns + ` FROM ` + tableDevice + ` WHERE device_id = $1`

	listDevicesSQL = `SELECT ` + deviceColumns + ` FROM ` + tableDevice + ` ORDER BY last_seen_at DESC, device_id`

	assignDeviceSQL = `UPDATE ` + tableDevice + ` SET role = $2, account_id = $3 WHERE device_id = $1`

	clearParentAccountsSQL = `DELETE FROM ` + tableParentAccount + ` WHERE device_id = $1`

	addParentAccountsSQL = `INSERT INTO ` + tableParentAccount + ` (device_id, account_id) SELECT $1, unnest($2::text[])`

	deleteDeviceSQL = `DELETE FROM ` + tableDevice + ` WHERE device_id = $1`

	listAccountsSQL = `SELECT account_id, name FROM ` + tableAccount + ` ORDER BY name, account_id`

	upsertAccountSQL = `INSERT INTO ` + tableAccount + ` (account_id, name) VALUES ($1, $2)
		ON CONFLICT (account_id) DO UPDATE SET name = EXCLUDED.name WHERE ` + tableAccount + `.name <> EXCLUDED.name`

	// An unchanged manifest writes nothing: phones republish every couple of minutes, the disk is a 5400 rpm HDD.
	upsertTopicSQL = `INSERT INTO ` + tableTopicState + ` (source_id, account_id, topic_id, owner_device_id, manifest, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (source_id, account_id, topic_id) DO UPDATE SET
			owner_device_id = EXCLUDED.owner_device_id, manifest = EXCLUDED.manifest, updated_at = EXCLUDED.updated_at
		WHERE ` + tableTopicState + `.manifest <> EXCLUDED.manifest
			OR ` + tableTopicState + `.owner_device_id <> EXCLUDED.owner_device_id`

	deleteUnlistedBlobsSQL = `DELETE FROM ` + tableTopicBlob + `
		WHERE source_id = $1 AND account_id = $2 AND topic_id = $3 AND blob_key <> ALL($4::text[])`

	missingBlobsSQL = `SELECT listed.blob_key FROM unnest($4::text[]) AS listed(blob_key)
		WHERE NOT EXISTS (SELECT 1 FROM ` + tableTopicBlob + ` stored
			WHERE stored.source_id = $1 AND stored.account_id = $2 AND stored.topic_id = $3
				AND stored.blob_key = listed.blob_key)`

	upsertBlobSQL = `INSERT INTO ` + tableTopicBlob + ` (source_id, account_id, topic_id, blob_key, data)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (source_id, account_id, topic_id, blob_key) DO UPDATE SET data = EXCLUDED.data`

	// Only a parent still linked to the account is served: unlinking it, or taking its role away, stops its data
	// reaching that account's devices. A shared state comes back with the next linked parent that publishes it.
	stateOfSQL = `SELECT state.source_id, state.topic_id, state.manifest FROM ` + tableTopicState + ` state
		JOIN ` + tableParentAccount + ` link ON link.device_id = state.owner_device_id AND link.account_id = state.account_id
		WHERE state.account_id = $1
		ORDER BY state.source_id, state.topic_id`

	blobSQL = `SELECT blob.data FROM ` + tableTopicBlob + ` blob
		JOIN ` + tableTopicState + ` state USING (source_id, account_id, topic_id)
		JOIN ` + tableParentAccount + ` link ON link.device_id = state.owner_device_id AND link.account_id = state.account_id
		WHERE blob.source_id = $1 AND blob.account_id = $2 AND blob.topic_id = $3 AND blob.blob_key = $4`

	foreignKeyViolation = "23503"
)

// DeviceRepository is the PostgreSQL DeviceStore.
type DeviceRepository struct {
	pool *pgxpool.Pool
}

// NewDeviceRepository wires the repository to its pool.
func NewDeviceRepository(pool *pgxpool.Pool) *DeviceRepository {
	return &DeviceRepository{pool: pool}
}

// Touch records a device's visit, creating it on the first one, and returns its stored row.
func (r *DeviceRepository) Touch(ctx context.Context, device Device) (Device, error) {
	row := r.pool.QueryRow(ctx, touchDeviceSQL, device.ID, device.Name, device.FirstSeenAt, device.LastSeenAt)
	return scanDevice(row)
}

// Find returns the device, or nil when it never said hello.
func (r *DeviceRepository) Find(ctx context.Context, deviceID string) (*Device, error) {
	device, err := scanDevice(r.pool.QueryRow(ctx, findDeviceSQL, deviceID))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &device, nil
}

// List returns every device, most recently seen first.
func (r *DeviceRepository) List(ctx context.Context) ([]Device, error) {
	rows, err := r.pool.Query(ctx, listDevicesSQL)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (Device, error) { return scanDevice(row) })
}

// Assign sets what the device may do: the one account a child reads, or the accounts a parent exchanges data for.
func (r *DeviceRepository) Assign(ctx context.Context, deviceID string, role Role, accountIDs []string) error {
	childAccountID := ""
	parentAccountIDs := []string{}
	switch {
	case role == RoleChild && len(accountIDs) > 0:
		childAccountID = accountIDs[0]
	case role == RoleParent:
		parentAccountIDs = accountIDs
	}
	return pgx.BeginFunc(ctx, r.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, assignDeviceSQL, deviceID, role, childAccountID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, clearParentAccountsSQL, deviceID); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, addParentAccountsSQL, deviceID, parentAccountIDs)
		return err
	})
}

// AnnounceAccounts makes the accounts available for linking; a known one with an unchanged name writes nothing.
func (r *DeviceRepository) AnnounceAccounts(ctx context.Context, accounts []Account) error {
	for _, account := range accounts {
		if _, err := r.pool.Exec(ctx, upsertAccountSQL, account.ID, account.Name); err != nil {
			return err
		}
	}
	return nil
}

// Delete forgets the device and, for a parent, everything it published. It reappears as unassigned on its next hello.
func (r *DeviceRepository) Delete(ctx context.Context, deviceID string) error {
	_, err := r.pool.Exec(ctx, deleteDeviceSQL, deviceID)
	return err
}

// Accounts lists the accounts parents announced or published for.
func (r *DeviceRepository) Accounts(ctx context.Context) ([]Account, error) {
	rows, err := r.pool.Query(ctx, listAccountsSQL)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (Account, error) {
		var account Account
		err := row.Scan(&account.ID, &account.Name)
		return account, err
	})
}

func scanDevice(row pgx.Row) (Device, error) {
	var device Device
	err := row.Scan(&device.ID, &device.Name, &device.Role, &device.AccountID, &device.FirstSeenAt, &device.LastSeenAt, &device.ParentAccountIDs)
	return device, err
}

// TopicRepository is the PostgreSQL TopicStore.
type TopicRepository struct {
	pool *pgxpool.Pool
}

// NewTopicRepository wires the repository to its pool.
func NewTopicRepository(pool *pgxpool.Pool) *TopicRepository {
	return &TopicRepository{pool: pool}
}

// Publish replaces the topic's state, drops the blobs it no longer lists and returns those it lists but lacks.
func (r *TopicRepository) Publish(ctx context.Context, ownerDeviceID string, p TopicPublication, at int64) ([]string, error) {
	missing := []string{}
	err := pgx.BeginFunc(ctx, r.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, upsertAccountSQL, p.AccountID, p.AccountName); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, upsertTopicSQL, p.SourceID, p.AccountID, p.TopicID, ownerDeviceID, p.Manifest, at); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, deleteUnlistedBlobsSQL, p.SourceID, p.AccountID, p.TopicID, p.BlobKeys); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, missingBlobsSQL, p.SourceID, p.AccountID, p.TopicID, p.BlobKeys)
		if err != nil {
			return err
		}
		keys, err := pgx.CollectRows(rows, pgx.RowTo[string])
		if err != nil {
			return err
		}
		missing = append(missing, keys...)
		return nil
	})
	return missing, err
}

// PutBlob stores a blob; false when its topic was never published.
func (r *TopicRepository) PutBlob(ctx context.Context, ref BlobRef, data []byte) (bool, error) {
	_, err := r.pool.Exec(ctx, upsertBlobSQL, ref.SourceID, ref.AccountID, ref.TopicID, ref.Key, data)
	var pgError *pgconn.PgError
	if errors.As(err, &pgError) && pgError.Code == foreignKeyViolation {
		return false, nil
	}
	return err == nil, err
}

// StateOf returns the topics of every parent linked to the account.
func (r *TopicRepository) StateOf(ctx context.Context, accountID string) ([]TopicState, error) {
	rows, err := r.pool.Query(ctx, stateOfSQL, accountID)
	if err != nil {
		return nil, err
	}
	states, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (TopicState, error) {
		var state TopicState
		err := row.Scan(&state.SourceID, &state.TopicID, &state.Manifest)
		return state, err
	})
	if err != nil {
		return nil, err
	}
	if states == nil {
		return []TopicState{}, nil
	}
	return states, nil
}

// Blob returns the blob's bytes, or nil when it isn't stored (yet).
func (r *TopicRepository) Blob(ctx context.Context, ref BlobRef) ([]byte, error) {
	var data []byte
	err := r.pool.QueryRow(ctx, blobSQL, ref.SourceID, ref.AccountID, ref.TopicID, ref.Key).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if data == nil {
		data = []byte{}
	}
	return data, nil
}
