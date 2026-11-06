package ticwatch

import (
	"context"
	"embed"
	"fmt"
	"html/template"
	"net/http"
	"time"
)

const (
	statsTemplate      = "templates/stats.html"
	statsPageName      = "stats.html"
	headerContentType  = "Content-Type"
	contentTypeHTML    = "text/html; charset=utf-8"
	statsTimeLayout    = "2006-01-02 15:04"
	noTimePlaceholder  = "-"
	bytesUnit          = 1024
	byteUnitPrefixes   = "KMGTP"
	byteSizeFormat     = "%.1f %ciB"
	plainByteFormat    = "%d B"
	thousandsSeparator = ' '
	thousandsGroup     = 3
)

//go:embed templates/stats.html
var templateFiles embed.FS

// TableStats is one table's row count, on-disk size (with indexes and TOAST) and the time span of its rows.
type TableStats struct {
	Name         string
	Rows         int64
	SizeBytes    int64
	FirstAt      *int64
	LastAt       *int64
	LastReceived *time.Time
}

// SampleTypeStats is how many samples of one sensor type the server holds and over which time span.
type SampleTypeStats struct {
	Code         int32
	Rows         int64
	FirstAt      int64
	LastAt       int64
	LastReceived time.Time
}

// Name is the phone's name of the type.
func (s SampleTypeStats) Name() string { return sensorTypeName(s.Code) }

// HistoryOverview is what the stats page shows.
type HistoryOverview struct {
	DatabaseBytes int64
	Tables        []TableStats
	SampleTypes   []SampleTypeStats
}

// HistoryStatsStore summarises the stored history.
type HistoryStatsStore interface {
	Overview(ctx context.Context) (HistoryOverview, error)
}

type statsPageData struct {
	HistoryOverview
	GeneratedAt time.Time
}

func newStatsPage() *template.Template {
	return template.Must(template.New(statsPageName).Funcs(template.FuncMap{
		"bytes":    formatBytes,
		"count":    formatCount,
		"epoch":    formatEpochMillis,
		"epochPtr": formatOptionalEpochMillis,
		"time":     formatTime,
		"timePtr":  formatOptionalTime,
	}).ParseFS(templateFiles, statsTemplate))
}

func (a *App) statsPage(w http.ResponseWriter, r *http.Request) error {
	overview, err := a.stats.Overview(r.Context())
	if err != nil {
		return err
	}
	w.Header().Set(headerContentType, contentTypeHTML)
	return a.statsTemplate.Execute(w, statsPageData{HistoryOverview: overview, GeneratedAt: a.now()})
}

func formatBytes(size int64) string {
	if size < bytesUnit {
		return fmt.Sprintf(plainByteFormat, size)
	}
	value := float64(size)
	prefix := 0
	for value /= bytesUnit; value >= bytesUnit && prefix < len(byteUnitPrefixes)-1; value /= bytesUnit {
		prefix++
	}
	return fmt.Sprintf(byteSizeFormat, value, byteUnitPrefixes[prefix])
}

func formatCount(n int64) string {
	digits := fmt.Sprint(n)
	var out []rune
	for i, digit := range digits {
		if i > 0 && (len(digits)-i)%thousandsGroup == 0 {
			out = append(out, thousandsSeparator)
		}
		out = append(out, digit)
	}
	return string(out)
}

func formatEpochMillis(ms int64) string { return formatTime(time.UnixMilli(ms)) }

func formatOptionalEpochMillis(ms *int64) string {
	if ms == nil {
		return noTimePlaceholder
	}
	return formatEpochMillis(*ms)
}

func formatTime(t time.Time) string { return t.Local().Format(statsTimeLayout) }

func formatOptionalTime(t *time.Time) string {
	if t == nil {
		return noTimePlaceholder
	}
	return formatTime(*t)
}
