package myapplication

import (
	"errors"
	"slices"
	"strings"
)

// PingResponse answers the discovery probe.
type PingResponse struct {
	Service    string `json:"service"`
	APIVersion int    `json:"apiVersion"`
	ServerTime int64  `json:"serverTime"`
}

// HelloRequest introduces a device; the first one creates its row in the admin page.
type HelloRequest struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	// Accounts are the Librus accounts a parent phone is logged in to: what the admin page offers for linking.
	Accounts []Account `json:"accounts"`
}

// Validate implements the request's shape check.
func (r HelloRequest) Validate() error {
	if strings.TrimSpace(r.DeviceID) == "" || len(r.DeviceID) > maxDeviceIDLength {
		return errors.New("invalid deviceId")
	}
	if len(r.Accounts) > maxAccountsPerHello {
		return errors.New("too many accounts")
	}
	for _, account := range r.Accounts {
		if strings.TrimSpace(account.ID) == "" || len(account.ID) > maxAccountIDLength {
			return errors.New("invalid account id")
		}
	}
	return nil
}

// HelloResponse tells a device what the admin allowed it to do so far.
type HelloResponse struct {
	Role Role `json:"role"`
	// AccountID is the one account a child reads.
	AccountID string `json:"accountId"`
	// AccountIDs are the accounts a parent exchanges data for.
	AccountIDs []string `json:"accountIds"`
}

// TopicPublication is one parent's full current state of one topic for one account.
type TopicPublication struct {
	SourceID    string   `json:"sourceId"`
	AccountID   string   `json:"accountId"`
	AccountName string   `json:"accountName"`
	TopicID     string   `json:"topicId"`
	Manifest    string   `json:"manifest"`
	BlobKeys    []string `json:"blobKeys"`
}

// Validate implements the request's shape check.
func (p TopicPublication) Validate() error {
	if p.SourceID == "" || p.AccountID == "" || p.TopicID == "" {
		return errors.New("sourceId, accountId and topicId are required")
	}
	return nil
}

// PublishResult names the blobs the server still lacks for the publication.
type PublishResult struct {
	MissingBlobKeys []string `json:"missingBlobKeys"`
}

// TopicState is one parent's state of one topic, as a device reads it.
type TopicState struct {
	SourceID string `json:"sourceId"`
	TopicID  string `json:"topicId"`
	Manifest string `json:"manifest"`
}

// AccountState is everything a device may read for one account.
type AccountState struct {
	Topics []TopicState `json:"topics"`
}

// Device is one row of the admin page.
type Device struct {
	ID          string
	Name        string
	Role        Role
	AccountID   string
	FirstSeenAt int64
	LastSeenAt  int64
	// ParentAccountIDs are the accounts a parent is linked to.
	ParentAccountIDs []string
}

// linkedTo says whether a parent may exchange data for the account.
func (d Device) linkedTo(accountID string) bool {
	return slices.Contains(d.ParentAccountIDs, accountID)
}

// AccountPickers is one admin page dropdown per linked account, each preselecting it; a blank one picks another.
func (d Device) AccountPickers() []string {
	switch d.Role {
	case RoleParent:
		return append(slices.Clone(d.ParentAccountIDs), "")
	case RoleChild:
		return []string{d.AccountID}
	default:
		return []string{""}
	}
}

// LacksAccount says the device has a role but nothing to exchange yet.
func (d Device) LacksAccount() bool {
	return (d.Role == RoleChild && d.AccountID == "") || (d.Role == RoleParent && len(d.ParentAccountIDs) == 0)
}

// Account is a Librus account a parent phone is logged in to.
type Account struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// BlobRef names one blob of one parent's topic.
type BlobRef struct {
	SourceID  string
	AccountID string
	TopicID   string
	Key       string
}
