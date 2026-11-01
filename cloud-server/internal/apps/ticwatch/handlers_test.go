package ticwatch

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"
)

const samplesPage = SamplesPath + "?types=1&from=0&to=9"

type fakeSamples struct {
	query       SampleQuery
	rollupQuery RollupQuery
	inserted    []Sample
	deviceID    string
	stored      int
}

func (f *fakeSamples) Insert(_ context.Context, deviceID string, samples []Sample) (int, error) {
	f.deviceID, f.inserted = deviceID, samples
	return f.stored, nil
}

func (f *fakeSamples) Find(_ context.Context, query SampleQuery) ([]StoredSample, error) {
	f.query = query
	return []StoredSample{}, nil
}

func (f *fakeSamples) Rollups(_ context.Context, query RollupQuery) ([]RollupPoint, error) {
	f.rollupQuery = query
	return []RollupPoint{}, nil
}

type fakeRoutePoints struct{ limit int32 }

func (f *fakeRoutePoints) Upsert(context.Context, string, []RoutePoint) (int, error) { return 0, nil }

func (f *fakeRoutePoints) Find(_ context.Context, _, _ int64, limit int32) ([]RoutePoint, error) {
	f.limit = limit
	return []RoutePoint{}, nil
}

type fakeEcg struct{ recordings []EcgRecording }

func (f *fakeEcg) Upsert(_ context.Context, _ string, recordings []EcgRecording) (int, error) {
	f.recordings = recordings
	return len(recordings), nil
}

type harness struct {
	samples *fakeSamples
	routes  *fakeRoutePoints
	ecg     *fakeEcg
	mux     *http.ServeMux
}

func newHarness() *harness {
	h := &harness{samples: &fakeSamples{}, routes: &fakeRoutePoints{}, ecg: &fakeEcg{}, mux: http.NewServeMux()}
	now := func() time.Time { return time.UnixMilli(1234) }
	New(h.samples, h.routes, h.ecg, now).RegisterRoutes(h.mux)
	return h
}

func (h *harness) get(target string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.mux.ServeHTTP(w, httptest.NewRequest(http.MethodGet, target, nil))
	return w
}

func (h *harness) post(target, body string, withDevice bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, target, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	if withDevice {
		r.Header.Set(DeviceHeader, "phone")
	}
	w := httptest.NewRecorder()
	h.mux.ServeHTTP(w, r)
	return w
}

func TestPing(t *testing.T) {
	got := newHarness().get(PingPath).Body.String()
	want := `{"service":"ticwatch-cloud","apiVersion":1,"serverTime":1234}`
	if got != want {
		t.Errorf("got %s", got)
	}
}

func TestSampleTypesForms(t *testing.T) {
	for _, form := range []string{"types=1,2", "types=1&types=2"} {
		h := newHarness()
		w := h.get(SamplesPath + "?" + form + "&from=0&to=9&limit=5")
		if w.Code != http.StatusOK || !reflect.DeepEqual(h.samples.query.Types, []int32{1, 2}) {
			t.Errorf("%s: status %d, types %v", form, w.Code, h.samples.query.Types)
		}
	}
}

func TestSampleCursorParsed(t *testing.T) {
	h := newHarness()
	h.get(samplesPage + "&limit=5&afterRecordedAt=7&afterId=8")
	query := h.samples.query
	if query.AfterRecordedAt == nil || *query.AfterRecordedAt != 7 || query.AfterID == nil || *query.AfterID != 8 {
		t.Errorf("cursor not parsed: %+v", query)
	}
	h = newHarness()
	h.get(samplesPage + "&limit=5")
	if h.samples.query.AfterRecordedAt != nil || h.samples.query.AfterID != nil {
		t.Errorf("unexpected cursor: %+v", h.samples.query)
	}
}

func TestLimitClamped(t *testing.T) {
	cases := map[string]int32{"0": MinPageSize, "-3": MinPageSize, "17": 17, "5000": MaxPageSize, "5001": MaxPageSize}
	for raw, want := range cases {
		h := newHarness()
		h.get(samplesPage + "&limit=" + raw)
		if h.samples.query.Limit != want {
			t.Errorf("samples limit=%s: got %d", raw, h.samples.query.Limit)
		}
		h.get(RoutePointsPath + "?from=0&to=9&limit=" + raw)
		if h.routes.limit != want {
			t.Errorf("route limit=%s: got %d", raw, h.routes.limit)
		}
	}
}

func TestMissingOrInvalidParamsAre400(t *testing.T) {
	targets := []string{
		SamplesPath + "?from=0&to=9&limit=1",
		SamplesPath + "?types=1&to=9&limit=1",
		SamplesPath + "?types=1&from=x&to=9&limit=1",
		samplesPage,
		samplesPage + "&limit=x",
		samplesPage + "&limit=1&afterId=x",
		RollupsPath + "?type=1&resolution=DAY&from=0&to=9",
		RollupsPath + "?type=1&resolution=day&from=0&to=9&zone=UTC",
		RollupsPath + "?type=1&resolution=WEEK&from=0&to=9&zone=UTC",
		RollupsPath + "?type=1&from=0&to=9&zone=UTC",
		RoutePointsPath + "?from=0&to=9",
	}
	for _, target := range targets {
		if w := newHarness().get(target); w.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d", target, w.Code)
		}
	}
}

func TestRollupResolutions(t *testing.T) {
	cases := map[string]RollupResolution{"FIVE_MINUTES": ResolutionFiveMinutes, "HOUR": ResolutionHour, "DAY": ResolutionDay}
	for name, want := range cases {
		h := newHarness()
		w := h.get(RollupsPath + "?type=1&resolution=" + name + "&from=0&to=9&zone=Europe/Warsaw")
		if w.Code != http.StatusOK || h.samples.rollupQuery.Resolution != want || h.samples.rollupQuery.Zone != "Europe/Warsaw" {
			t.Errorf("%s: status %d, %+v", name, w.Code, h.samples.rollupQuery)
		}
		if rollupBucketSQL[want] == "" {
			t.Errorf("%s: no bucket SQL", name)
		}
	}
}

func TestUploadRequiresDeviceHeader(t *testing.T) {
	if w := newHarness().post(SamplesPath, `{"samples":[]}`, false); w.Code != http.StatusBadRequest {
		t.Errorf("status %d", w.Code)
	}
}

func TestUploadResult(t *testing.T) {
	h := newHarness()
	h.samples.stored = 1
	body := `{"samples":[{"type":1,"value":72.5,"recordedAt":5,"endedAt":null,"source":0,"snapshot":false},
		{"type":1,"value":72.5,"recordedAt":5,"source":0,"snapshot":true}]}`
	w := h.post(SamplesPath, body, true)
	if w.Code != http.StatusOK || w.Body.String() != `{"received":2,"stored":1}` {
		t.Errorf("%d %s", w.Code, w.Body)
	}
	if h.samples.deviceID != "phone" || h.samples.inserted[0].EndedAt != nil || !h.samples.inserted[1].Snapshot {
		t.Errorf("stored %+v", h.samples.inserted)
	}
}

func TestUploadRejectsMissingFields(t *testing.T) {
	bodies := map[string]string{
		SamplesPath:       `{"samples":[{"type":1,"recordedAt":5,"source":0,"snapshot":false}]}`,
		RoutePointsPath:   `{"points":[{"recordedAt":5,"source":0,"latitude":1.0}]}`,
		EcgRecordingsPath: `{"recordings":[{"startedAt":5}]}`,
	}
	for path, body := range bodies {
		if w := newHarness().post(path, body, true); w.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d", path, w.Code)
		}
	}
	for _, path := range []string{SamplesPath, RoutePointsPath, EcgRecordingsPath} {
		if w := newHarness().post(path, `{}`, true); w.Code != http.StatusBadRequest {
			t.Errorf("%s without list: status %d", path, w.Code)
		}
	}
}

func TestEcgPayloadStaysVerbatim(t *testing.T) {
	h := newHarness()
	payload := `{"t":[1,2],"nested":{"ok":true}}`
	w := h.post(EcgRecordingsPath, `{"recordings":[{"startedAt":5,"payload":`+payload+`}]}`, true)
	if w.Code != http.StatusOK || string(h.ecg.recordings[0].Payload) != payload {
		t.Errorf("%d, payload %s", w.Code, h.ecg.recordings[0].Payload)
	}
}

func TestDoubleFormatMatchesJava(t *testing.T) {
	cases := map[float64]string{
		0:                      "0.0",
		72:                     "72.0",
		36.6:                   "36.6",
		-1.5:                   "-1.5",
		0.001:                  "0.001",
		9999999:                "9999999.0",
		1e7:                    "1.0E7",
		1.5e10:                 "1.5E10",
		0.0001:                 "1.0E-4",
		1.2345e-5:              "1.2345E-5",
		5e-324:                 "4.9E-324",
		1.7976931348623157e308: "1.7976931348623157E308",
		0.30000000000000004:    "0.30000000000000004",
	}
	for value, want := range cases {
		encoded, err := json.Marshal(JavaDouble(value))
		if err != nil || string(encoded) != want {
			t.Errorf("%v: got %s, %v; want %s", value, encoded, err, want)
		}
	}
}

func TestNullablesSerialiseAsNull(t *testing.T) {
	encoded, _ := json.Marshal(RoutePoint{RecordedAt: 1, Latitude: 2, Longitude: 3})
	want := `{"recordedAt":1,"source":0,"latitude":2.0,"longitude":3.0,"altitudeMeters":null,"horizontalAccuracyMeters":null}`
	if string(encoded) != want {
		t.Errorf("got %s", encoded)
	}
}
