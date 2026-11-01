package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const (
	// MaxBodyBytes bounds an upload; the netbook has 2 GB of RAM and the phone's batches stay far below this.
	MaxBodyBytes = 32 << 20

	jsonSubtypeSuffix = "+json"
	mediaApplication  = "application/"
	listSeparator     = ","

	bits32 = 32
	bits64 = 64
	base10 = 10
)

// DecodeJSON reads a JSON body into dst. Like Spring's @RequestBody, a non-JSON content type is a 415, except that
// a request with neither content type nor body is merely a missing body (400).
func DecodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	body, err := ReadBody(w, r)
	if err != nil {
		return err
	}
	contentType := r.Header.Get(headerContentType)
	if contentType == "" && len(body) == 0 {
		return BadRequest("missing body")
	}
	if !isJSONMediaType(contentType) {
		return &StatusError{Status: http.StatusUnsupportedMediaType, Reason: contentType}
	}
	if err := json.Unmarshal(body, dst); err != nil {
		return BadRequest("malformed JSON: %v", err)
	}
	return nil
}

// ReadBody reads a body of at most MaxBodyBytes; anything longer is a 413.
func ReadBody(w http.ResponseWriter, r *http.Request) ([]byte, error) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxBodyBytes))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return nil, &StatusError{Status: http.StatusRequestEntityTooLarge, Reason: "body too large"}
		}
		return nil, BadRequest("unreadable body: %v", err)
	}
	return body, nil
}

func isJSONMediaType(contentType string) bool {
	mediaType, _, err := mime.ParseMediaType(contentType)
	if err != nil {
		return false
	}
	return mediaType == contentTypeJSON ||
		(strings.HasPrefix(mediaType, mediaApplication) && strings.HasSuffix(mediaType, jsonSubtypeSuffix))
}

// RequiredHeader returns a header that must be present; an empty value counts as present.
func RequiredHeader(r *http.Request, name string) (string, error) {
	values, ok := r.Header[http.CanonicalHeaderKey(name)]
	if !ok || len(values) == 0 {
		return "", BadRequest("missing header %s", name)
	}
	return values[0], nil
}

// RequiredString returns a query parameter that must be present; an empty value counts as present.
func RequiredString(query url.Values, name string) (string, error) {
	if !query.Has(name) {
		return "", BadRequest("missing parameter %s", name)
	}
	return query.Get(name), nil
}

// RequiredInt64 parses a mandatory integer query parameter.
func RequiredInt64(query url.Values, name string) (int64, error) {
	return requiredInt(query, name, bits64)
}

// RequiredInt32 parses a mandatory integer query parameter that must fit 32 bits.
func RequiredInt32(query url.Values, name string) (int32, error) {
	value, err := requiredInt(query, name, bits32)
	return int32(value), err
}

// OptionalInt64 parses an integer query parameter: absent or empty is nil, malformed is an error.
func OptionalInt64(query url.Values, name string) (*int64, error) {
	raw := strings.TrimSpace(query.Get(name))
	if raw == "" {
		return nil, nil
	}
	value, err := strconv.ParseInt(raw, base10, bits64)
	if err != nil {
		return nil, BadRequest("invalid parameter %s", name)
	}
	return &value, nil
}

// Int32List parses a mandatory list parameter the way Spring binds a collection: `name=1,2` or `name=1&name=2`.
// Commas split only a single occurrence, empty items are skipped, and `name=` is a valid empty list.
func Int32List(query url.Values, name string) ([]int32, error) {
	occurrences, ok := query[name]
	if !ok {
		return nil, BadRequest("missing parameter %s", name)
	}
	items := occurrences
	if len(occurrences) == 1 {
		items = strings.Split(occurrences[0], listSeparator)
	}
	list := make([]int32, 0, len(items))
	for _, item := range items {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		value, err := strconv.ParseInt(item, base10, bits32)
		if err != nil {
			return nil, BadRequest("invalid parameter %s", name)
		}
		list = append(list, int32(value))
	}
	return list, nil
}

func requiredInt(query url.Values, name string, bitSize int) (int64, error) {
	raw := strings.TrimSpace(query.Get(name))
	if raw == "" {
		return 0, BadRequest("missing parameter %s", name)
	}
	value, err := strconv.ParseInt(raw, base10, bitSize)
	if err != nil {
		return 0, BadRequest("invalid parameter %s", name)
	}
	return value, nil
}
