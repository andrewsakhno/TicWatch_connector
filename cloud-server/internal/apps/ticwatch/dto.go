package ticwatch

import (
	"encoding/json"
	"errors"
)

var errMissingField = errors.New("missing required field")

// PingResponse answers the discovery probe.
type PingResponse struct {
	Service    string `json:"service"`
	APIVersion int    `json:"apiVersion"`
	ServerTime int64  `json:"serverTime"`
}

// UploadResult: Stored = newly inserted; the rest were already on the server. All Received are safe to mark uploaded.
type UploadResult struct {
	Received int `json:"received"`
	Stored   int `json:"stored"`
}

// Sample is one uploaded reading; times are epoch ms, Type/Source the phone's stable codes.
type Sample struct {
	Type       int32
	Value      JavaDouble
	RecordedAt int64
	EndedAt    *int64
	Source     int32
	Snapshot   bool
}

// UnmarshalJSON rejects a missing or null required field, as the Kotlin data class does.
func (s *Sample) UnmarshalJSON(data []byte) error {
	var wire struct {
		Type       *int32      `json:"type"`
		Value      *JavaDouble `json:"value"`
		RecordedAt *int64      `json:"recordedAt"`
		EndedAt    *int64      `json:"endedAt"`
		Source     *int32      `json:"source"`
		Snapshot   *bool       `json:"snapshot"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	if wire.Type == nil || wire.Value == nil || wire.RecordedAt == nil || wire.Source == nil || wire.Snapshot == nil {
		return errMissingField
	}
	*s = Sample{
		Type:       *wire.Type,
		Value:      *wire.Value,
		RecordedAt: *wire.RecordedAt,
		EndedAt:    wire.EndedAt,
		Source:     *wire.Source,
		Snapshot:   *wire.Snapshot,
	}
	return nil
}

// SampleUpload is the body of POST samples.
type SampleUpload struct {
	Samples []Sample `json:"samples"`
}

// Size implements upload.
func (u SampleUpload) Size() int { return len(u.Samples) }

// Validate implements upload.
func (u SampleUpload) Validate() error { return requirePresent(u.Samples == nil) }

// StoredSample is a sample read back, with its server ID as the paging tie-breaker.
type StoredSample struct {
	ID         int64      `json:"id"`
	Type       int32      `json:"type"`
	Value      JavaDouble `json:"value"`
	RecordedAt int64      `json:"recordedAt"`
	EndedAt    *int64     `json:"endedAt"`
	Source     int32      `json:"source"`
	Snapshot   bool       `json:"snapshot"`
}

// RollupPoint is one aggregated bucket.
type RollupPoint struct {
	BucketStart int64      `json:"bucketStart"`
	Min         JavaDouble `json:"min"`
	Max         JavaDouble `json:"max"`
	Sum         JavaDouble `json:"sum"`
	Count       int64      `json:"count"`
}

// RoutePoint is one workout GPS fix.
type RoutePoint struct {
	RecordedAt               int64       `json:"recordedAt"`
	Source                   int32       `json:"source"`
	Latitude                 JavaDouble  `json:"latitude"`
	Longitude                JavaDouble  `json:"longitude"`
	AltitudeMeters           *JavaDouble `json:"altitudeMeters"`
	HorizontalAccuracyMeters *JavaDouble `json:"horizontalAccuracyMeters"`
}

// UnmarshalJSON rejects a missing or null required field.
func (p *RoutePoint) UnmarshalJSON(data []byte) error {
	var wire struct {
		RecordedAt               *int64      `json:"recordedAt"`
		Source                   *int32      `json:"source"`
		Latitude                 *JavaDouble `json:"latitude"`
		Longitude                *JavaDouble `json:"longitude"`
		AltitudeMeters           *JavaDouble `json:"altitudeMeters"`
		HorizontalAccuracyMeters *JavaDouble `json:"horizontalAccuracyMeters"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	if wire.RecordedAt == nil || wire.Source == nil || wire.Latitude == nil || wire.Longitude == nil {
		return errMissingField
	}
	*p = RoutePoint{
		RecordedAt:               *wire.RecordedAt,
		Source:                   *wire.Source,
		Latitude:                 *wire.Latitude,
		Longitude:                *wire.Longitude,
		AltitudeMeters:           wire.AltitudeMeters,
		HorizontalAccuracyMeters: wire.HorizontalAccuracyMeters,
	}
	return nil
}

// RoutePointUpload is the body of POST route-points.
type RoutePointUpload struct {
	Points []RoutePoint `json:"points"`
}

// Size implements upload.
func (u RoutePointUpload) Size() int { return len(u.Points) }

// Validate implements upload.
func (u RoutePointUpload) Validate() error { return requirePresent(u.Points == nil) }

// EcgRecording is one rhythm strip; Payload is opaque to the server and stored verbatim.
type EcgRecording struct {
	StartedAt int64
	Payload   json.RawMessage
}

// UnmarshalJSON rejects a missing required field; a JSON null payload is a value, as a Jackson JsonNode field has it.
func (e *EcgRecording) UnmarshalJSON(data []byte) error {
	var wire struct {
		StartedAt *int64          `json:"startedAt"`
		Payload   json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	if wire.StartedAt == nil || wire.Payload == nil {
		return errMissingField
	}
	*e = EcgRecording{StartedAt: *wire.StartedAt, Payload: wire.Payload}
	return nil
}

// EcgRecordingUpload is the body of POST ecg-recordings.
type EcgRecordingUpload struct {
	Recordings []EcgRecording `json:"recordings"`
}

// Size implements upload.
func (u EcgRecordingUpload) Size() int { return len(u.Recordings) }

// Validate implements upload.
func (u EcgRecordingUpload) Validate() error { return requirePresent(u.Recordings == nil) }

// DeviationEpisode is one heart rate deviation and its answer; a re-sent uid replaces the stored row.
type DeviationEpisode struct {
	UID         string
	Direction   int32
	State       int32
	StartedAt   int64
	EndedAt     int64
	PeakBpm     JavaDouble
	Reasons     []int32
	DetectedAt  int64
	AskedAt     *int64
	AnsweredAt  *int64
	DiscardedAt *int64
	// Optional since V3: the detection's corridor and context, nil when unknown.
	Minor            bool
	Intensity        *int32
	ThresholdBpm     *JavaDouble
	BaselineBpm      *JavaDouble
	ExcessBpmMinutes *JavaDouble
	WornSince        *int64
	StepsBefore      *JavaDouble
	RmssdBefore      *JavaDouble
	SleepScore       *JavaDouble
	FatiguePercent   *JavaDouble
	LastMedicationAt *int64
}

// UnmarshalJSON rejects a missing or null required field.
func (e *DeviationEpisode) UnmarshalJSON(data []byte) error {
	var wire struct {
		UID              *string     `json:"uid"`
		Direction        *int32      `json:"direction"`
		State            *int32      `json:"state"`
		StartedAt        *int64      `json:"startedAt"`
		EndedAt          *int64      `json:"endedAt"`
		PeakBpm          *JavaDouble `json:"peakBpm"`
		Reasons          []int32     `json:"reasons"`
		DetectedAt       *int64      `json:"detectedAt"`
		AskedAt          *int64      `json:"askedAt"`
		AnsweredAt       *int64      `json:"answeredAt"`
		DiscardedAt      *int64      `json:"discardedAt"`
		Minor            *bool       `json:"minor"`
		Intensity        *int32      `json:"intensity"`
		ThresholdBpm     *JavaDouble `json:"thresholdBpm"`
		BaselineBpm      *JavaDouble `json:"baselineBpm"`
		ExcessBpmMinutes *JavaDouble `json:"excessBpmMinutes"`
		WornSince        *int64      `json:"wornSince"`
		StepsBefore      *JavaDouble `json:"stepsBefore"`
		RmssdBefore      *JavaDouble `json:"rmssdBefore"`
		SleepScore       *JavaDouble `json:"sleepScore"`
		FatiguePercent   *JavaDouble `json:"fatiguePercent"`
		LastMedicationAt *int64      `json:"lastMedicationAt"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	if wire.UID == nil || wire.Direction == nil || wire.State == nil || wire.StartedAt == nil || wire.EndedAt == nil ||
		wire.PeakBpm == nil || wire.Reasons == nil || wire.DetectedAt == nil {
		return errMissingField
	}
	*e = DeviationEpisode{
		UID:              *wire.UID,
		Direction:        *wire.Direction,
		State:            *wire.State,
		StartedAt:        *wire.StartedAt,
		EndedAt:          *wire.EndedAt,
		PeakBpm:          *wire.PeakBpm,
		Reasons:          wire.Reasons,
		DetectedAt:       *wire.DetectedAt,
		AskedAt:          wire.AskedAt,
		AnsweredAt:       wire.AnsweredAt,
		DiscardedAt:      wire.DiscardedAt,
		Minor:            wire.Minor != nil && *wire.Minor,
		Intensity:        wire.Intensity,
		ThresholdBpm:     wire.ThresholdBpm,
		BaselineBpm:      wire.BaselineBpm,
		ExcessBpmMinutes: wire.ExcessBpmMinutes,
		WornSince:        wire.WornSince,
		StepsBefore:      wire.StepsBefore,
		RmssdBefore:      wire.RmssdBefore,
		SleepScore:       wire.SleepScore,
		FatiguePercent:   wire.FatiguePercent,
		LastMedicationAt: wire.LastMedicationAt,
	}
	return nil
}

// DeviationEpisodeUpload is the body of POST deviation-episodes.
type DeviationEpisodeUpload struct {
	Episodes []DeviationEpisode `json:"episodes"`
}

// Size implements upload.
func (u DeviationEpisodeUpload) Size() int { return len(u.Episodes) }

// Validate implements upload.
func (u DeviationEpisodeUpload) Validate() error { return requirePresent(u.Episodes == nil) }

func requirePresent(missing bool) error {
	if missing {
		return errMissingField
	}
	return nil
}
