package ticwatch

import (
	"math"
	"strconv"
	"strings"
)

const (
	plainFormatMin = 1e-3
	plainFormatMax = 1e7

	formatFloat64     = 64
	shortestPrecision = -1
	// Java's scientific form always has a fractional digit.
	minScientificPrecision = 1

	jsonNaN              = `"NaN"`
	jsonPositiveInfinity = `"Infinity"`
	jsonNegativeInfinity = `"-Infinity"`
)

// JavaDouble is a float64 that serialises as Jackson writes a Java double (72.0, 1.0E7, 1.0E-4), where encoding/json
// would write 72, 1e+07 and 0.0001.
type JavaDouble float64

// MarshalJSON implements json.Marshaler.
func (d JavaDouble) MarshalJSON() ([]byte, error) {
	return []byte(d.format()), nil
}

func (d JavaDouble) format() string {
	value := float64(d)
	switch {
	case math.IsNaN(value):
		return jsonNaN
	case math.IsInf(value, 1):
		return jsonPositiveInfinity
	case math.IsInf(value, -1):
		return jsonNegativeInfinity
	}
	if magnitude := math.Abs(value); value == 0 || (magnitude >= plainFormatMin && magnitude < plainFormatMax) {
		return formatPlain(value)
	}
	return formatScientific(value)
}

func formatPlain(value float64) string {
	text := strconv.FormatFloat(value, 'f', shortestPrecision, formatFloat64)
	if !strings.Contains(text, ".") {
		text += ".0"
	}
	return text
}

func formatScientific(value float64) string {
	text := strconv.FormatFloat(value, 'e', shortestPrecision, formatFloat64)
	if !strings.Contains(text, ".") {
		text = strconv.FormatFloat(value, 'e', minScientificPrecision, formatFloat64)
	}
	mantissa, exponentText, _ := strings.Cut(text, "e")
	exponent, _ := strconv.Atoi(exponentText)
	return mantissa + "E" + strconv.Itoa(exponent)
}
