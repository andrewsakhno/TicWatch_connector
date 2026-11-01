package httpx

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strings"
	"testing"
)

func TestInt32ListForms(t *testing.T) {
	cases := []struct {
		query string
		want  []int32
		fails bool
	}{
		{query: "types=1,2", want: []int32{1, 2}},
		{query: "types=1&types=2", want: []int32{1, 2}},
		{query: "types=1,%202", want: []int32{1, 2}},
		{query: "types=", want: []int32{}},
		{query: "types=1,,2", want: []int32{1, 2}},
		{query: "", fails: true},
		{query: "types=x", fails: true},
		{query: "types=1,2&types=3", fails: true},
		{query: "types=99999999999", fails: true},
	}
	for _, c := range cases {
		values, _ := url.ParseQuery(c.query)
		got, err := Int32List(values, "types")
		if c.fails {
			if err == nil {
				t.Errorf("%q: expected an error, got %v", c.query, got)
			}
			continue
		}
		if err != nil || !reflect.DeepEqual(got, c.want) {
			t.Errorf("%q: got %v, %v; want %v", c.query, got, err, c.want)
		}
	}
}

func TestRequiredAndOptionalIntegers(t *testing.T) {
	values, _ := url.ParseQuery("a=5&b=x&c=&big=99999999999")
	if got, err := RequiredInt64(values, "a"); err != nil || got != 5 {
		t.Errorf("a: %v, %v", got, err)
	}
	for _, name := range []string{"b", "c", "missing"} {
		if _, err := RequiredInt64(values, name); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
	if _, err := RequiredInt32(values, "big"); err == nil {
		t.Error("big: expected a 32-bit overflow error")
	}
	if got, err := RequiredInt64(values, "big"); err != nil || got != 99999999999 {
		t.Errorf("big as int64: %v, %v", got, err)
	}
	for _, name := range []string{"c", "missing"} {
		if got, err := OptionalInt64(values, name); err != nil || got != nil {
			t.Errorf("%s: %v, %v", name, got, err)
		}
	}
	if _, err := OptionalInt64(values, "b"); err == nil {
		t.Error("b: expected an error")
	}
}

func TestDecodeJSONStatuses(t *testing.T) {
	cases := []struct {
		contentType string
		body        string
		status      int
	}{
		{contentType: "application/json", body: `{}`},
		{contentType: "application/json; charset=UTF-8", body: `{}`},
		{contentType: "application/vnd.x+json", body: `{}`},
		{contentType: "", body: "", status: http.StatusBadRequest},
		{contentType: "", body: `{}`, status: http.StatusUnsupportedMediaType},
		{contentType: "text/plain", body: `{}`, status: http.StatusUnsupportedMediaType},
		{contentType: "application/json", body: `{`, status: http.StatusBadRequest},
		{contentType: "application/json", body: `{} {}`, status: http.StatusBadRequest},
	}
	for _, c := range cases {
		r := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(c.body))
		if c.contentType != "" {
			r.Header.Set(headerContentType, c.contentType)
		}
		var dst map[string]any
		err := DecodeJSON(httptest.NewRecorder(), r, &dst)
		var status *StatusError
		switch {
		case c.status == 0 && err != nil:
			t.Errorf("%q %q: %v", c.contentType, c.body, err)
		case c.status != 0 && (!errors.As(err, &status) || status.Status != c.status):
			t.Errorf("%q %q: got %v, want status %d", c.contentType, c.body, err, c.status)
		}
	}
}
