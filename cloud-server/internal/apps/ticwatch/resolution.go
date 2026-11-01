package ticwatch

import "strings"

// RollupResolution is a bucket tier, mirroring phone-app's `history/RollupResolution` (same names).
type RollupResolution int

const (
	ResolutionFiveMinutes RollupResolution = iota
	ResolutionHour
	ResolutionDay
)

var rollupResolutionByName = map[string]RollupResolution{
	"FIVE_MINUTES": ResolutionFiveMinutes,
	"HOUR":         ResolutionHour,
	"DAY":          ResolutionDay,
}

// rollupBucketSQL yields a tier's bucket start in ms: fixed-width tiers floor epoch ms, DAY starts at local midnight
// of the requested zone. Copied from backend/'s RollupResolution.kt.
var rollupBucketSQL = map[RollupResolution]string{
	ResolutionFiveMinutes: "(recorded_at / 300000) * 300000",
	ResolutionHour:        "(recorded_at / 3600000) * 3600000",
	ResolutionDay: "(EXTRACT(EPOCH FROM (date_trunc('day', to_timestamp(recorded_at / 1000.0) AT TIME ZONE @zone) " +
		"AT TIME ZONE @zone)) * 1000)::BIGINT",
}

// ParseRollupResolution resolves a wire name; names are case-sensitive.
func ParseRollupResolution(name string) (RollupResolution, bool) {
	resolution, ok := rollupResolutionByName[strings.TrimSpace(name)]
	return resolution, ok
}
