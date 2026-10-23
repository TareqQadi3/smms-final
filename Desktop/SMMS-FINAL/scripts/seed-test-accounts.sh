#!/bin/bash
# Creates one TEST account per role at King Fahad Central Hospital (seed data)
# through the app's own API, and sets the shared TEST password on them.
# Idempotent: existing accounts are not duplicated; their password is reset.
# Usage: bash Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh
set -euo pipefail

API="${API:-http://localhost/api}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@1234}"
TEST_PASS="${TEST_PASS:-Aa123456}"
EXTRA_RESET="${EXTRA_RESET:-Nasser,Tareq}"   # accounts created by hand in the UI

export API ADMIN_USER ADMIN_PASS TEST_PASS EXTRA_RESET
exec python3 - <<'PY'
import json, os, sys, urllib.request, urllib.parse, urllib.error

API, TEST_PASS = os.environ["API"], os.environ["TEST_PASS"]
REGION = "11111111-1111-1111-1111-111111111111"   # تجمع جازان الصحي
CENTER = "22222222-2222-2222-2222-222222222222"   # مستشفى الملك فهد المركزي
CR = {"residential": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      "hospital":    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      "it":          "cccccccc-cccc-cccc-cccc-cccccccccccc",
      "medical":     "dddddddd-dddd-dddd-dddd-dddddddddddd"}

def call(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach the app at {API} ({e.reason}). Wait until it is running, then retry.")

st, res = call("POST", "/auth/login", {"username": os.environ["ADMIN_USER"], "password": os.environ["ADMIN_PASS"]})
if st != 200:
    sys.exit(f"Admin login failed (HTTP {st}). Is the app running and is the admin password correct?")
token = res["token"]

def all_users():
    users, page = [], 0
    while True:
        st, res = call("GET", f"/admin/users?page={page}&size=100", token=token)
        if st != 200: sys.exit(f"Listing users failed (HTTP {st}): {res}")
        users += res["content"]
        if res.get("last", True): return users
        page += 1

def base(username, role, name_ar, name_en, **extra):
    d = {"username": username, "password": TEST_PASS, "role": role,
         "fullName": name_en, "fullNameAr": name_ar}
    if role == "region_supervisor": d["regionId"] = REGION
    else: d["centerId"] = CENTER
    d.update(extra)
    return d

ACCOUNTS = [
    base("cr.residential", "control_room", "غرفة تحكم صيانة الإسكان", "Control Room - Residential", controlRoomId=CR["residential"]),
    base("cr.hospital",    "control_room", "غرفة تحكم صيانة المستشفى", "Control Room - Hospital",   controlRoomId=CR["hospital"]),
    base("cr.it",          "control_room", "غرفة تحكم تقنية المعلومات", "Control Room - IT",        controlRoomId=CR["it"]),
    base("cr.medical",     "control_room", "غرفة تحكم الصيانة الطبية", "Control Room - Medical",    controlRoomId=CR["medical"]),
    base("tech.residential", "technician", "فني صيانة الإسكان", "Technician - Residential", controlRoomIds=[CR["residential"]]),
    base("tech.hospital",    "technician", "فني صيانة المستشفى", "Technician - Hospital",  controlRoomIds=[CR["hospital"]]),
    base("tech.it",          "technician", "فني تقنية المعلومات", "Technician - IT",       controlRoomIds=[CR["it"]]),
    base("tech.medical",     "technician", "فني الصيانة الطبية", "Technician - Medical",   controlRoomIds=[CR["medical"]]),
    base("maint.manager",    "maintenance_manager",       "مدير الصيانة",       "Maintenance Manager"),
    base("elec.manager",     "electrical_manager",        "مدير الكهرباء",      "Electrical Manager"),
    base("warehouse",        "warehouse",                 "أمين المستودع",      "Warehouse Keeper"),
    base("opco.manager",     "operating_company_manager", "مدير شركة التشغيل", "Operating Company Manager"),
    base("resident",   "resident",   "ساكن تجريبي", "Test Resident", controlRoomIds=[CR["residential"]],
         complexName="مجمع الإسكان", buildingNumber="1", unitNumber="101"),
    base("department", "department", "قسم الطوارئ", "Emergency Department",
         controlRoomIds=[CR["hospital"], CR["it"], CR["medical"]],
         departmentName="قسم الطوارئ", departmentSupervisor="مشرف الطوارئ", extensionNumber="1234"),
    base("other.user", "other", "مستخدم آخر", "Other User", controlRoomIds=[CR["hospital"]], jobTitle="منسق"),
]

existing = {u["username"].lower(): u for u in all_users()}
ok, failed = [], []

for acc in ACCOUNTS:
    u = existing.get(acc["username"].lower())
    if u is None:
        st, res = call("POST", "/admin/users", acc, token)
        if st == 201: ok.append((acc["username"], acc["role"], "created")); continue
        failed.append((acc["username"], f"create HTTP {st}: {res}")); continue
    st, res = call("POST", f"/admin/users/{u['id']}/reset-password?newPassword={urllib.parse.quote(TEST_PASS)}", token=token)
    (ok if st == 204 else failed).append((u["username"], u["role"], "existed, password reset") if st == 204 else (u["username"], f"reset HTTP {st}: {res}"))

for name in filter(None, os.environ["EXTRA_RESET"].split(",")):
    u = existing.get(name.strip().lower())
    if u is None:
        print(f"note: '{name}' not found, skipped"); continue
    st, res = call("POST", f"/admin/users/{u['id']}/reset-password?newPassword={urllib.parse.quote(TEST_PASS)}", token=token)
    (ok if st == 204 else failed).append((u["username"], u["role"], "password reset") if st == 204 else (u["username"], f"reset HTTP {st}: {res}"))

for username, *_ in ok:
    st, _ = call("POST", "/auth/login", {"username": username, "password": TEST_PASS})
    if st != 200: failed.append((username, f"login check HTTP {st}"))

print(f"\n{'username':<18} {'role':<26} result")
for row in ok: print(f"{row[0]:<18} {row[1]:<26} {row[2]}")
print(f"\nVerified login with the test password for {len(ok) - sum(1 for f in failed if 'login check' in f[1])} account(s).")
if failed:
    print("\nFAILED:"); [print(" ", f[0], "-", f[1]) for f in failed]; sys.exit(1)
print("All done.")
PY
