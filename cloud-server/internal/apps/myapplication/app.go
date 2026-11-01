package myapplication

import (
	"context"
	"embed"
	"html/template"
	"io/fs"
	"net/http"
	"time"

	"cloud-server/internal/platform/httpx"
	"cloud-server/internal/platform/migrate"
)

const (
	migrationsDir = "migrations"
	adminTemplate = "templates/admin.html"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

//go:embed templates/admin.html
var templateFiles embed.FS

// DeviceStore keeps the devices seen so far and what the admin allowed them.
type DeviceStore interface {
	Touch(ctx context.Context, device Device) (Device, error)
	Find(ctx context.Context, deviceID string) (*Device, error)
	List(ctx context.Context) ([]Device, error)
	Assign(ctx context.Context, deviceID string, role Role, accountIDs []string) error
	Delete(ctx context.Context, deviceID string) error
	Accounts(ctx context.Context) ([]Account, error)
	AnnounceAccounts(ctx context.Context, accounts []Account) error
}

// TopicStore keeps what parents published.
type TopicStore interface {
	Publish(ctx context.Context, ownerDeviceID string, publication TopicPublication, at int64) (missing []string, err error)
	PutBlob(ctx context.Context, ref BlobRef, data []byte) (stored bool, err error)
	StateOf(ctx context.Context, accountID string) ([]TopicState, error)
	Blob(ctx context.Context, ref BlobRef) ([]byte, error)
}

// App is the MyApplication app of the platform.
type App struct {
	devices       DeviceStore
	topics        TopicStore
	adminPassword string
	adminPage     *template.Template
	now           func() time.Time
}

// New wires the app to its stores. A blank adminPassword locks the admin page.
func New(devices DeviceStore, topics TopicStore, adminPassword string, now func() time.Time) *App {
	page := template.Must(template.New(adminPageName).Funcs(template.FuncMap{"seenAt": formatSeenAt}).
		ParseFS(templateFiles, adminTemplate))
	return &App{devices: devices, topics: topics, adminPassword: adminPassword, adminPage: page, now: now}
}

// Name implements app.App.
func (a *App) Name() string { return appName }

// Migrations implements app.App.
func (a *App) Migrations() migrate.Source {
	files, err := fs.Sub(migrationFiles, migrationsDir)
	if err != nil {
		panic(err) // migrationsDir is a compile-time embed of this package.
	}
	return migrate.Source{Schema: Schema, Files: files}
}

// PublicPaths implements app.App: every path passes the platform's key check, because this app authorizes requests
// itself - by the device's role, and by the admin password for the admin page.
func (a *App) PublicPaths() []string {
	return []string{
		PingPath, HelloPath, ParentTopicsPath, ParentBlobsPath, ParentStatePath, ChildStatePath, ChildBlobsPath,
		AdminPath, AdminAssignPath, AdminDeletePath,
	}
}

// RegisterRoutes implements app.App.
func (a *App) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc(http.MethodGet+" "+PingPath, httpx.Handle(a.ping))
	mux.HandleFunc(http.MethodPost+" "+HelloPath, httpx.Handle(a.hello))
	mux.HandleFunc(http.MethodPost+" "+ParentTopicsPath, httpx.Handle(a.publishTopic))
	mux.HandleFunc(http.MethodPut+" "+ParentBlobsPath, httpx.Handle(a.putBlob))
	mux.HandleFunc(http.MethodGet+" "+ParentBlobsPath, httpx.Handle(a.parentBlob))
	mux.HandleFunc(http.MethodGet+" "+ParentStatePath, httpx.Handle(a.parentState))
	mux.HandleFunc(http.MethodGet+" "+ChildStatePath, httpx.Handle(a.childState))
	mux.HandleFunc(http.MethodGet+" "+ChildBlobsPath, httpx.Handle(a.childBlob))
	mux.HandleFunc(http.MethodGet+" "+AdminPath, httpx.Handle(a.requireAdmin(a.adminList)))
	mux.HandleFunc(http.MethodPost+" "+AdminAssignPath, httpx.Handle(a.requireAdmin(a.adminAssign)))
	mux.HandleFunc(http.MethodPost+" "+AdminDeletePath, httpx.Handle(a.requireAdmin(a.adminDelete)))
}
