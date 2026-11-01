// Package myapplication is the exchange of the MyApplication (MyLibrus) Android apps: parents publish each topic's full
// state for the accounts the admin page links them to and read each other's, children only read their one account.
// contract.go mirrors `CloudContract.kt` in
// the MyLibrus repo (core-librus, core/parentsync/cloud) - keep both in sync.
package myapplication

// Schema holds this app's tables.
const Schema = "myapplication"

const appName = "myapplication"

const (
	// BasePath prefixes every route, so the app shares the server with the other hosted apps.
	BasePath = "/myapplication"
	apiPath  = BasePath + "/api/v1"

	// PingPath is the discovery probe; phones scanning the subnet accept a host only when ServiceName answers.
	PingPath         = apiPath + "/ping"
	HelloPath        = apiPath + "/hello"
	ParentTopicsPath = apiPath + "/parent/topics"
	ParentBlobsPath  = apiPath + "/parent/blobs"
	ParentStatePath  = apiPath + "/parent/state"
	ChildStatePath   = apiPath + "/child/state"
	ChildBlobsPath   = apiPath + "/child/blobs"

	AdminPath       = BasePath + "/admin"
	AdminAssignPath = AdminPath + "/assign"
	AdminDeletePath = AdminPath + "/delete"

	// DeviceHeader carries the phone's random install id; together with its role it is the device's credential.
	DeviceHeader = "X-Device-Id"

	ServiceName = "myapplication-sync"
	APIVersion  = 1

	maxDeviceIDLength   = 100
	maxDeviceNameLength = 100
	maxAccountIDLength  = 100
	maxAccountsPerHello = 50
)

const (
	paramSource  = "sourceId"
	paramAccount = "accountId"
	paramTopic   = "topicId"
	paramKey     = "key"
)

// Role says what a device may do; a new device is RoleUnassigned and can do nothing until the admin picks one.
type Role string

const (
	RoleUnassigned Role = "UNASSIGNED"
	RoleParent     Role = "PARENT"
	RoleChild      Role = "CHILD"
)

// Roles lists every role in the order the admin page offers them.
var Roles = []Role{RoleUnassigned, RoleParent, RoleChild}

func (r Role) valid() bool {
	for _, role := range Roles {
		if r == role {
			return true
		}
	}
	return false
}
