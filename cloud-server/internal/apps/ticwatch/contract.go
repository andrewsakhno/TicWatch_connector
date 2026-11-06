// Package ticwatch is the cloud history API of the TicWatch phone app: a wire-compatible port of backend/'s `cloud`
// package. contract.go mirrors `CloudApi.kt` in backend/ and phone-app/ - keep all three in sync.
package ticwatch

// Schema holds this app's tables.
const Schema = "ticwatch"

const appName = "ticwatch"

const (
	// BasePath prefixes every route.
	BasePath = "/api/v1"
	// PingPath is the discovery probe; phones scanning the subnet accept a host only when ServiceName answers.
	PingPath              = BasePath + "/ping"
	SamplesPath           = BasePath + "/samples"
	RollupsPath           = BasePath + "/rollups"
	RoutePointsPath       = BasePath + "/route-points"
	EcgRecordingsPath     = BasePath + "/ecg-recordings"
	DeviationEpisodesPath = BasePath + "/deviation-episodes"
	// StatsPath is the HTML overview of the stored history (sizes, rows per table and per sensor type).
	StatsPath = "/" + appName

	// DeviceHeader identifies the uploading phone install; stored with every row, never used to filter reads.
	DeviceHeader = "X-Device-Id"

	ServiceName = "ticwatch-cloud"
	APIVersion  = 1

	MinPageSize = 1
	MaxPageSize = 5000
)

const (
	paramTypes           = "types"
	paramType            = "type"
	paramFrom            = "from"
	paramTo              = "to"
	paramAfterRecordedAt = "afterRecordedAt"
	paramAfterID         = "afterId"
	paramLimit           = "limit"
	paramResolution      = "resolution"
	paramZone            = "zone"
)
