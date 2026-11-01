package myapplication

import (
	"crypto/subtle"
	"net/http"
	"slices"
	"time"

	"cloud-server/internal/platform/httpx"
)

const (
	adminPageName = "admin.html"
	adminUser     = "admin"

	headerAuthenticate = "WWW-Authenticate"
	basicChallenge     = `Basic realm="MyApplication admin"`
	contentTypeHTML    = "text/html; charset=utf-8"
	seenAtLayout       = "2006-01-02 15:04 UTC"

	formDeviceID = "device_id"
	formRole     = "role"
	formAccount  = "account_id"
)

// exchangeRow is who exchanges data for one account, as the admin page's overview shows it.
type exchangeRow struct {
	Account  Account
	Parents  []string
	Children []string
}

// adminPageData is what admin.html renders.
type adminPageData struct {
	Devices    []Device
	Accounts   []Account
	Exchange   []exchangeRow
	Roles      []Role
	RoleChild  Role
	RoleParent Role
	AssignPath string
	DeletePath string
}

// requireAdmin guards the page with HTTP Basic: a child on the same Wi-Fi must not be able to promote its own phone.
func (a *App) requireAdmin(next httpx.HandlerFunc) httpx.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		if a.adminPassword == "" {
			return &httpx.StatusError{Status: http.StatusForbidden, Reason: "admin password is not configured"}
		}
		user, password, ok := r.BasicAuth()
		userMatches := subtle.ConstantTimeCompare([]byte(user), []byte(adminUser)) == 1
		passwordMatches := subtle.ConstantTimeCompare([]byte(password), []byte(a.adminPassword)) == 1
		if !ok || !userMatches || !passwordMatches {
			w.Header().Set(headerAuthenticate, basicChallenge)
			return &httpx.StatusError{Status: http.StatusUnauthorized, Reason: "admin credentials required"}
		}
		return next(w, r)
	}
}

func (a *App) adminList(w http.ResponseWriter, r *http.Request) error {
	devices, err := a.devices.List(r.Context())
	if err != nil {
		return err
	}
	accounts, err := a.devices.Accounts(r.Context())
	if err != nil {
		return err
	}
	w.Header().Set(headerContentType, contentTypeHTML)
	return a.adminPage.Execute(w, adminPageData{
		Devices:    devices,
		Accounts:   accounts,
		Exchange:   exchangeOverview(devices, accounts),
		Roles:      Roles,
		RoleChild:  RoleChild,
		RoleParent: RoleParent,
		AssignPath: AdminAssignPath,
		DeletePath: AdminDeletePath,
	})
}

func exchangeOverview(devices []Device, accounts []Account) []exchangeRow {
	rows := make([]exchangeRow, 0, len(accounts))
	for _, account := range accounts {
		row := exchangeRow{Account: account}
		for _, device := range devices {
			switch {
			case device.Role == RoleParent && device.linkedTo(account.ID):
				row.Parents = append(row.Parents, device.Name)
			case device.Role == RoleChild && device.AccountID == account.ID:
				row.Children = append(row.Children, device.Name)
			}
		}
		rows = append(rows, row)
	}
	return rows
}

func (a *App) adminAssign(w http.ResponseWriter, r *http.Request) error {
	role := Role(r.FormValue(formRole))
	if !role.valid() {
		return httpx.BadRequest("unknown role")
	}
	accountIDs := pickedAccounts(r.PostForm[formAccount])
	// Refused rather than dropped, so a Save never looks done while losing the choice.
	if role == RoleUnassigned && len(accountIDs) > 0 {
		return httpx.BadRequest("an account needs the %s or %s role", RoleParent, RoleChild)
	}
	if role == RoleChild && len(accountIDs) > 1 {
		return httpx.BadRequest("a %s reads one account", RoleChild)
	}
	if err := a.devices.Assign(r.Context(), r.FormValue(formDeviceID), role, accountIDs); err != nil {
		return err
	}
	http.Redirect(w, r, AdminPath, http.StatusSeeOther)
	return nil
}

// pickedAccounts drops the blank dropdowns and accounts picked twice.
func pickedAccounts(values []string) []string {
	picked := []string{}
	for _, value := range values {
		if value != "" && !slices.Contains(picked, value) {
			picked = append(picked, value)
		}
	}
	return picked
}

func (a *App) adminDelete(w http.ResponseWriter, r *http.Request) error {
	if err := a.devices.Delete(r.Context(), r.FormValue(formDeviceID)); err != nil {
		return err
	}
	http.Redirect(w, r, AdminPath, http.StatusSeeOther)
	return nil
}

func formatSeenAt(epochMillis int64) string {
	return time.UnixMilli(epochMillis).UTC().Format(seenAtLayout)
}
