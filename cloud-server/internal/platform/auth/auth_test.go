package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

const (
	testKey    = "secret"
	publicPath = "/ping"
	guarded    = "/data"
)

func TestRequire(t *testing.T) {
	cases := []struct {
		name   string
		key    StaticKey
		path   string
		header string
		want   int
	}{
		{name: "open server", key: "", path: guarded, want: http.StatusOK},
		{name: "blank key is open", key: "  ", path: guarded, want: http.StatusOK},
		{name: "public path", key: testKey, path: publicPath, want: http.StatusOK},
		{name: "valid key", key: testKey, path: guarded, header: "Bearer secret", want: http.StatusOK},
		{name: "no header", key: testKey, path: guarded, want: http.StatusUnauthorized},
		{name: "wrong key", key: testKey, path: guarded, header: "Bearer other", want: http.StatusUnauthorized},
		{name: "prefix of the key", key: testKey, path: guarded, header: "Bearer sec", want: http.StatusUnauthorized},
		{name: "no bearer prefix", key: testKey, path: guarded, header: "secret", want: http.StatusUnauthorized},
		{name: "unknown path guarded", key: testKey, path: "/nope", want: http.StatusUnauthorized},
	}
	next := http.HandlerFunc(func(http.ResponseWriter, *http.Request) {})
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodGet, c.path, nil)
			if c.header != "" {
				r.Header.Set(headerAuthorization, c.header)
			}
			w := httptest.NewRecorder()
			Require(c.key, []string{publicPath}, next).ServeHTTP(w, r)
			if w.Code != c.want {
				t.Errorf("status %d, want %d", w.Code, c.want)
			}
		})
	}
}
