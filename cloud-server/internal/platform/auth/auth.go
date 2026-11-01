// Package auth guards the HTTP API with a shared secret.
package auth

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"cloud-server/internal/platform/httpx"
)

const (
	headerAuthorization = "Authorization"
	bearerPrefix        = "Bearer "
)

// KeySource says which key protects a request; "" means it is open. The seam for per-app keys: a future source can
// pick the key by the request's path.
type KeySource interface {
	KeyFor(r *http.Request) string
}

// StaticKey protects every request with one key; a blank key leaves the server open (home LAN).
type StaticKey string

// KeyFor implements KeySource.
func (k StaticKey) KeyFor(*http.Request) string {
	return strings.TrimSpace(string(k))
}

// Require answers 401 unless the request carries `Authorization: Bearer <key>`; publicPaths are always let through.
func Require(keys KeySource, publicPaths []string, next http.Handler) http.Handler {
	public := make(map[string]struct{}, len(publicPaths))
	for _, path := range publicPaths {
		public[path] = struct{}{}
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, open := public[r.URL.Path]; open {
			next.ServeHTTP(w, r)
			return
		}
		key := keys.KeyFor(r)
		if key == "" || presentsKey(r, key) {
			next.ServeHTTP(w, r)
			return
		}
		httpx.WriteError(w, r, http.StatusUnauthorized)
	})
}

func presentsKey(r *http.Request, key string) bool {
	presented, ok := strings.CutPrefix(r.Header.Get(headerAuthorization), bearerPrefix)
	return ok && subtle.ConstantTimeCompare([]byte(presented), []byte(key)) == 1
}
