package migrate

import (
	"reflect"
	"testing"
	"testing/fstest"
)

func TestOrderedFilesSortsByVersionNumber(t *testing.T) {
	files := fstest.MapFS{
		"V10__later.sql": {},
		"V2__second.sql": {},
		"V1__first.sql":  {},
	}
	got, err := orderedFiles(files)
	want := []string{"V1__first.sql", "V2__second.sql", "V10__later.sql"}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Errorf("got %v, %v; want %v", got, err, want)
	}
}

func TestOrderedFilesRejectsForeignNames(t *testing.T) {
	if _, err := orderedFiles(fstest.MapFS{"notes.txt": {}}); err == nil {
		t.Error("expected an error")
	}
}
