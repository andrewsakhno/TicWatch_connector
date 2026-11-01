package myapplication

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"cloud-server/internal/platform/httpx"
)

const headerContentType = "Content-Type"
const contentTypeBinary = "application/octet-stream"

func (a *App) ping(w http.ResponseWriter, _ *http.Request) error {
	return httpx.WriteJSON(w, PingResponse{
		Service:    ServiceName,
		APIVersion: APIVersion,
		ServerTime: a.now().UnixMilli(),
	})
}

func (a *App) hello(w http.ResponseWriter, r *http.Request) error {
	var body HelloRequest
	if err := httpx.DecodeJSON(w, r, &body); err != nil {
		return err
	}
	if err := body.Validate(); err != nil {
		return httpx.BadRequest("%v", err)
	}
	at := a.now().UnixMilli()
	device, err := a.devices.Touch(r.Context(), Device{
		ID:          body.DeviceID,
		Name:        trimmedName(body.Name),
		FirstSeenAt: at,
		LastSeenAt:  at,
	})
	if err != nil {
		return err
	}
	// Only a parent has a say in what the admin page offers, or any phone on the Wi-Fi could fill it with junk.
	if device.Role == RoleParent {
		if err := a.devices.AnnounceAccounts(r.Context(), body.Accounts); err != nil {
			return err
		}
	}
	return httpx.WriteJSON(w, HelloResponse{Role: device.Role, AccountID: device.AccountID, AccountIDs: device.ParentAccountIDs})
}

func trimmedName(name string) string {
	runes := []rune(strings.TrimSpace(name))
	if len(runes) > maxDeviceNameLength {
		runes = runes[:maxDeviceNameLength]
	}
	return string(runes)
}

// deviceWithRole answers 403 unless the request's device exists and the admin gave it role.
func (a *App) deviceWithRole(r *http.Request, role Role) (*Device, error) {
	deviceID, err := httpx.RequiredHeader(r, DeviceHeader)
	if err != nil {
		return nil, err
	}
	device, err := a.devices.Find(r.Context(), deviceID)
	if err != nil {
		return nil, err
	}
	if device == nil || device.Role != role {
		return nil, &httpx.StatusError{Status: http.StatusForbidden, Reason: "device is not a " + string(role)}
	}
	return device, nil
}

// requireLinked answers 403 unless the admin linked the parent to the account: that is the isolation between families' children.
func requireLinked(device *Device, accountID string) error {
	if !device.linkedTo(accountID) {
		return &httpx.StatusError{Status: http.StatusForbidden, Reason: "device is not linked to the account"}
	}
	return nil
}

func (a *App) publishTopic(w http.ResponseWriter, r *http.Request) error {
	device, err := a.deviceWithRole(r, RoleParent)
	if err != nil {
		return err
	}
	var body TopicPublication
	if err := httpx.DecodeJSON(w, r, &body); err != nil {
		return err
	}
	if err := body.Validate(); err != nil {
		return httpx.BadRequest("%v", err)
	}
	if err := requireLinked(device, body.AccountID); err != nil {
		return err
	}
	if body.BlobKeys == nil {
		body.BlobKeys = []string{}
	}
	missing, err := a.topics.Publish(r.Context(), device.ID, body, a.now().UnixMilli())
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, PublishResult{MissingBlobKeys: missing})
}

func (a *App) putBlob(w http.ResponseWriter, r *http.Request) error {
	ref, err := a.linkedParentBlobRef(r)
	if err != nil {
		return err
	}
	data, err := httpx.ReadBody(w, r)
	if err != nil {
		return err
	}
	stored, err := a.topics.PutBlob(r.Context(), ref, data)
	if err != nil {
		return err
	}
	if !stored {
		return &httpx.StatusError{Status: http.StatusConflict, Reason: "publish the topic before its blobs"}
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (a *App) childState(w http.ResponseWriter, r *http.Request) error {
	device, err := a.deviceWithRole(r, RoleChild)
	if err != nil {
		return err
	}
	state, err := a.stateOf(r.Context(), device.AccountID)
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, AccountState{Topics: state})
}

// parentState lets a parent read what the other parents published, for the topics they edit together.
func (a *App) parentState(w http.ResponseWriter, r *http.Request) error {
	device, err := a.deviceWithRole(r, RoleParent)
	if err != nil {
		return err
	}
	accountID := r.URL.Query().Get(paramAccount)
	if accountID == "" {
		return httpx.BadRequest("%s is required", paramAccount)
	}
	if err := requireLinked(device, accountID); err != nil {
		return err
	}
	state, err := a.stateOf(r.Context(), accountID)
	if err != nil {
		return err
	}
	return httpx.WriteJSON(w, AccountState{Topics: state})
}

// stateOf is empty for a child the admin hasn't given an account yet.
func (a *App) stateOf(ctx context.Context, accountID string) ([]TopicState, error) {
	if accountID == "" {
		return []TopicState{}, nil
	}
	return a.topics.StateOf(ctx, accountID)
}

func (a *App) childBlob(w http.ResponseWriter, r *http.Request) error {
	device, err := a.deviceWithRole(r, RoleChild)
	if err != nil {
		return err
	}
	// The account comes from the device's row, never from the request: that is the isolation between siblings.
	ref, err := parseBlobRef(r.URL.Query(), device.AccountID)
	if err != nil {
		return err
	}
	return a.writeBlob(w, r, ref)
}

func (a *App) parentBlob(w http.ResponseWriter, r *http.Request) error {
	ref, err := a.linkedParentBlobRef(r)
	if err != nil {
		return err
	}
	return a.writeBlob(w, r, ref)
}

// linkedParentBlobRef reads a parent's blob request, whose account the admin must have linked the parent to.
func (a *App) linkedParentBlobRef(r *http.Request) (BlobRef, error) {
	device, err := a.deviceWithRole(r, RoleParent)
	if err != nil {
		return BlobRef{}, err
	}
	query := r.URL.Query()
	ref, err := parseBlobRef(query, query.Get(paramAccount))
	if err != nil {
		return BlobRef{}, err
	}
	return ref, requireLinked(device, ref.AccountID)
}

func (a *App) writeBlob(w http.ResponseWriter, r *http.Request, ref BlobRef) error {
	data, err := a.topics.Blob(r.Context(), ref)
	if err != nil {
		return err
	}
	if data == nil {
		return &httpx.StatusError{Status: http.StatusNotFound, Reason: "no such blob"}
	}
	w.Header().Set(headerContentType, contentTypeBinary)
	_, err = w.Write(data)
	return err
}

func parseBlobRef(query url.Values, accountID string) (BlobRef, error) {
	ref := BlobRef{
		SourceID:  query.Get(paramSource),
		AccountID: accountID,
		TopicID:   query.Get(paramTopic),
		Key:       query.Get(paramKey),
	}
	if ref.SourceID == "" || ref.AccountID == "" || ref.TopicID == "" || ref.Key == "" {
		return BlobRef{}, httpx.BadRequest("sourceId, accountId, topicId and key are required")
	}
	return ref, nil
}
