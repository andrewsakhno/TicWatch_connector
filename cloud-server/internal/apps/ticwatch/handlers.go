package ticwatch

import (
	"context"
	"net/http"

	"cloud-server/internal/platform/httpx"
)

// upload is a POST body: a list that must be present, of a known size.
type upload interface {
	Size() int
	Validate() error
}

// handleUpload is the shared POST flow: device header, body, store, UploadResult.
func handleUpload[U upload](store func(ctx context.Context, deviceID string, body U) (int, error)) httpx.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		deviceID, err := httpx.RequiredHeader(r, DeviceHeader)
		if err != nil {
			return err
		}
		var body U
		if err := httpx.DecodeJSON(w, r, &body); err != nil {
			return err
		}
		if err := body.Validate(); err != nil {
			return httpx.BadRequest("%v", err)
		}
		stored, err := store(r.Context(), deviceID, body)
		if err != nil {
			return err
		}
		return httpx.WriteJSON(w, UploadResult{Received: body.Size(), Stored: stored})
	}
}

func (a *App) storeSamples(ctx context.Context, deviceID string, body SampleUpload) (int, error) {
	return a.samples.Insert(ctx, deviceID, body.Samples)
}

func (a *App) storeRoutePoints(ctx context.Context, deviceID string, body RoutePointUpload) (int, error) {
	return a.routePoints.Upsert(ctx, deviceID, body.Points)
}

func (a *App) storeEcgRecordings(ctx context.Context, deviceID string, body EcgRecordingUpload) (int, error) {
	return a.ecgRecordings.Upsert(ctx, deviceID, body.Recordings)
}

func (a *App) ping(w http.ResponseWriter, _ *http.Request) error {
	return httpx.WriteJSON(w, PingResponse{
		Service:    ServiceName,
		APIVersion: APIVersion,
		ServerTime: a.now().UnixMilli(),
	})
}

func (a *App) findSamples(w http.ResponseWriter, r *http.Request) error {
	query, err := parseSampleQuery(r)
	if err != nil {
		return err
	}
	samples, err := a.samples.Find(r.Context(), query)
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, samples)
}

func (a *App) rollups(w http.ResponseWriter, r *http.Request) error {
	query, err := parseRollupQuery(r)
	if err != nil {
		return err
	}
	points, err := a.samples.Rollups(r.Context(), query)
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, points)
}

func (a *App) findRoutePoints(w http.ResponseWriter, r *http.Request) error {
	values := r.URL.Query()
	from, err := httpx.RequiredInt64(values, paramFrom)
	if err != nil {
		return err
	}
	to, err := httpx.RequiredInt64(values, paramTo)
	if err != nil {
		return err
	}
	limit, err := parseLimit(r)
	if err != nil {
		return err
	}
	points, err := a.routePoints.Find(r.Context(), from, to, limit)
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, points)
}

func parseSampleQuery(r *http.Request) (SampleQuery, error) {
	values := r.URL.Query()
	var query SampleQuery
	var err error
	if query.Types, err = httpx.Int32List(values, paramTypes); err != nil {
		return query, err
	}
	if query.From, err = httpx.RequiredInt64(values, paramFrom); err != nil {
		return query, err
	}
	if query.To, err = httpx.RequiredInt64(values, paramTo); err != nil {
		return query, err
	}
	if query.AfterRecordedAt, err = httpx.OptionalInt64(values, paramAfterRecordedAt); err != nil {
		return query, err
	}
	if query.AfterID, err = httpx.OptionalInt64(values, paramAfterID); err != nil {
		return query, err
	}
	query.Limit, err = parseLimit(r)
	return query, err
}

func parseRollupQuery(r *http.Request) (RollupQuery, error) {
	values := r.URL.Query()
	var query RollupQuery
	var err error
	if query.Type, err = httpx.RequiredInt32(values, paramType); err != nil {
		return query, err
	}
	resolution, known := ParseRollupResolution(values.Get(paramResolution))
	if !known {
		return query, httpx.BadRequest("invalid parameter %s", paramResolution)
	}
	query.Resolution = resolution
	if query.From, err = httpx.RequiredInt64(values, paramFrom); err != nil {
		return query, err
	}
	if query.To, err = httpx.RequiredInt64(values, paramTo); err != nil {
		return query, err
	}
	query.Zone, err = httpx.RequiredString(values, paramZone)
	return query, err
}

// parseLimit reads the mandatory page size, clamped to the contract's range.
func parseLimit(r *http.Request) (int32, error) {
	limit, err := httpx.RequiredInt32(r.URL.Query(), paramLimit)
	if err != nil {
		return 0, err
	}
	return min(max(limit, MinPageSize), MaxPageSize), nil
}
