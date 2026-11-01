// Package httpx holds the HTTP plumbing shared by every app: JSON responses, the error body, request decoding and
// query parameter parsing.
package httpx

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"
)

const (
	headerContentType = "Content-Type"
	contentTypeJSON   = "application/json"

	// Same shape as Spring Boot's default error body, so clients of the Kotlin server see no difference.
	errorTimestampLayout = "2006-01-02T15:04:05.000Z"
)

// StatusError makes a handler answer with Status instead of the catch-all 500.
type StatusError struct {
	Status int
	Reason string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("%d: %s", e.Status, e.Reason)
}

// BadRequest is the StatusError for a request the client got wrong.
func BadRequest(format string, args ...any) error {
	return &StatusError{Status: http.StatusBadRequest, Reason: fmt.Sprintf(format, args...)}
}

// HandlerFunc is an http.HandlerFunc that reports failures by returning them.
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// Handle adapts h: a StatusError becomes its status, anything else is logged and answered with a 500.
func Handle(h HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		err := h(w, r)
		if err == nil {
			return
		}
		var status *StatusError
		if errors.As(err, &status) {
			WriteError(w, r, status.Status)
			return
		}
		slog.Error("request failed", "method", r.Method, "path", r.URL.Path, "error", err)
		WriteError(w, r, http.StatusInternalServerError)
	}
}

// WriteJSON answers 200 with value as JSON.
func WriteJSON(w http.ResponseWriter, value any) error {
	body, err := marshal(value)
	if err != nil {
		return err
	}
	w.Header().Set(headerContentType, contentTypeJSON)
	_, err = w.Write(body)
	return err
}

// WriteError answers status with the standard error body.
func WriteError(w http.ResponseWriter, r *http.Request, status int) {
	body, err := marshal(errorBody{
		Timestamp: time.Now().UTC().Format(errorTimestampLayout),
		Status:    status,
		Error:     http.StatusText(status),
		Path:      r.URL.Path,
	})
	if err != nil {
		http.Error(w, http.StatusText(status), status)
		return
	}
	w.Header().Set(headerContentType, contentTypeJSON)
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

type errorBody struct {
	Timestamp string `json:"timestamp"`
	Status    int    `json:"status"`
	Error     string `json:"error"`
	Path      string `json:"path"`
}

// marshal is json.Marshal without HTML escaping, as Jackson writes it.
func marshal(value any) ([]byte, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buffer.Bytes(), "\n"), nil
}
