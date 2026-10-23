#!/bin/bash
# Seeds 15 TEST accounts (one per role) at King Fahad Central Hospital via the admin API.
# Dry run by default; never modifies existing accounts. Run with --help for usage.
set -euo pipefail

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required but was not found." >&2
  exit 1
fi

exec python3 - "$@" <<'PY'
import getpass, json, os, socket, sys, urllib.error, urllib.parse, urllib.request

HELP = """Usage: bash scripts/seed-test-accounts.sh [--apply] [--reset-password USERNAME ...]

Creates 15 test accounts (one per role) at King Fahad Central Hospital (KFCH-JZ)
through the app's admin API.

Default is a DRY RUN: shows what would happen and changes nothing.

Options:
  --apply                    Actually create the missing accounts.
  --reset-password USERNAME  With --apply: reset this EXISTING user's password to the
                             test password. Repeatable. Without this option no existing
                             account is ever changed. WARNING: the app's endpoint puts
                             the password in the URL, so nginx access logs will contain it.
  -h, --help                 Show this help.

Environment variables (optional):
  API                          API base URL (default http://localhost/api)
  ADMIN_USER                   Admin username (default admin)
  ADMIN_PASS                   Admin password (prompted, hidden, if not set)
  TEST_PASS                    Password for the new accounts (prompted twice if not set,
                               minimum 8 characters)
  SMMS_ALLOW_NON_CODESPACE=1   Allow running outside a GitHub Codespace or against a
                               non-localhost API URL.

Safety: writes are refused unless CODESPACES=true (set automatically in Codespaces)
or SMMS_ALLOW_NON_CODESPACE=1. Existing accounts are skipped, never modified.
"""

API = os.environ.get("API", "http://localhost/api").rstrip("/")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ALLOW = os.environ.get("SMMS_ALLOW_NON_CODESPACE") == "1"
REGION = "11111111-1111-1111-1111-111111111111"   # Jazan Health Cluster (JHC)
CENTER = "22222222-2222-2222-2222-222222222222"   # King Fahad Central Hospital (KFCH-JZ)
CR = {"residential": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      "hospital":    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      "it":          "cccccccc-cccc-cccc-cccc-cccccccccccc",
      "medical":     "dddddddd-dddd-dddd-dddd-dddddddddddd"}


def die(msg, code=1):
    print("\nERROR: " + msg, file=sys.stderr)
    sys.exit(code)


def parse_args(argv):
    apply, resets, i = False, [], 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            print(HELP); sys.exit(0)
        elif a == "--apply":
            apply = True
        elif a == "--reset-password":
            i += 1
            if i >= len(argv) or argv[i].startswith("-"):
                die("--reset-password needs a USERNAME. See --help.", 2)
            resets.append(argv[i])
        else:
            die(f"Unknown option '{a}'. See --help.", 2)
        i += 1
    return apply, resets


APPLY, RESETS = parse_args(sys.argv[1:])
if RESETS and not APPLY:
    print("Note: --reset-password only takes effect together with --apply (showing plan only).")

# ---- guards -------------------------------------------------------------
host = urllib.parse.urlparse(API).hostname or ""
if host not in ("localhost", "127.0.0.1") and not ALLOW:
    die(f"Refusing to use API host '{host}': only localhost/127.0.0.1 is allowed.\n"
        "Set SMMS_ALLOW_NON_CODESPACE=1 only if you really mean it.")
in_codespace = os.environ.get("CODESPACES") == "true"
if APPLY and not (in_codespace or ALLOW):
    die("Refusing to write: this does not look like a GitHub Codespace (CODESPACES is not 'true').\n"
        "Run it inside the Codespace, or set SMMS_ALLOW_NON_CODESPACE=1 if you really mean it.")


def call(method, path, body=None, token=None):
    """Returns (status, parsed_body). Never prints request data."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            try:
                return r.status, (json.loads(raw) if raw else None)
            except ValueError:
                return r.status, raw.decode(errors="replace")[:200]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:200].replace("\n", " ")
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        die(f"Cannot reach the app at {API} ({reason}).\nMake sure it is running, then try again.")


def items(res):
    return res.get("content", []) if isinstance(res, dict) else (res or [])


def get_list(path, token, what):
    st, res = call("GET", path, token=token)
    if st != 200:
        die(f"Could not read {what} (HTTP {st}): {res}")
    return items(res)


# ---- target info --------------------------------------------------------
print("Target")
print(f"  API URL      : {API}")
print(f"  CODESPACE    : {os.environ.get('CODESPACE_NAME') or '(not set)'}")
print(f"  Hostname     : {socket.gethostname()}")
print(f"  Mode         : {'APPLY' if APPLY else 'DRY RUN (no changes)'}")

# ---- admin login --------------------------------------------------------
admin_pass = os.environ.get("ADMIN_PASS") or getpass.getpass(f"Admin password for '{ADMIN_USER}' (hidden): ")
st, res = call("POST", "/auth/login", {"username": ADMIN_USER, "password": admin_pass})
if st != 200 or not isinstance(res, dict) or "token" not in res:
    die(f"Admin login failed (HTTP {st}). Check the admin username/password and that the app is running.")
token = res["token"]
del admin_pass

# ---- prerequisites ------------------------------------------------------
problems = []
regions = {r.get("id"): r for r in get_list("/admin/regions", token, "regions")}
centers = {c.get("id"): c for c in get_list("/admin/centers", token, "centers")}
rooms = {c.get("id"): c for c in get_list("/admin/control-rooms", token, "control rooms")}
if regions.get(REGION, {}).get("code") != "JHC":
    problems.append(f"region JHC ({REGION}) not found")
if centers.get(CENTER, {}).get("code") != "KFCH-JZ":
    problems.append(f"center KFCH-JZ ({CENTER}) not found")
for k, rid in CR.items():
    cr = rooms.get(rid)
    if cr is None:
        problems.append(f"control room '{k}' ({rid}) not found")
    elif (cr.get("center") or {}).get("id") != CENTER:
        problems.append(f"control room '{k}' does not belong to center KFCH-JZ")
if problems:
    die("Prerequisite check failed, nothing was changed:\n  - " + "\n  - ".join(problems))
print("  Prerequisites: region JHC, center KFCH-JZ and 4 control rooms found.")


def base(username, role, name_ar, name_en, **extra):
    d = {"username": username, "role": role, "fullName": name_en, "fullNameAr": name_ar}
    if role == "region_supervisor":
        d["regionId"] = REGION
    else:
        d["centerId"] = CENTER
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

# ---- plan ---------------------------------------------------------------
existing = {}
page = 0
while True:
    st, res = call("GET", f"/admin/users?page={page}&size=100", token=token)
    if st != 200:
        die(f"Could not list users (HTTP {st}): {res}")
    for u in items(res):
        existing[u["username"].lower()] = u
    if not isinstance(res, dict) or res.get("last", True):
        break
    page += 1

reset_names = {r.lower() for r in RESETS}
plan = []  # (username, role, action, note, account_or_None, user_or_None)
for acc in ACCOUNTS:
    key = acc["username"].lower()
    u = existing.get(key)
    if u is None:
        plan.append((acc["username"], acc["role"], "create", "", acc, None))
    elif key in reset_names:
        plan.append((u["username"], u.get("role", "?"), "reset-password", "explicitly requested", acc, u))
    else:
        diffs = []
        if u.get("role") != acc["role"]:
            diffs.append(f"role is {u.get('role')}")
        if (u.get("center") or {}).get("id") != CENTER:
            diffs.append("center differs")
        if diffs:
            plan.append((u["username"], u.get("role", "?"), "skip-mismatch", "exists - DIFFERENT " + ", ".join(diffs) + ", skipped", acc, u))
        else:
            plan.append((u["username"], u.get("role", "?"), "skip-exists", "exists - skipped", acc, u))
planned_names = {a["username"].lower() for a in ACCOUNTS}
for name in RESETS:
    if name.lower() in planned_names:
        continue
    u = existing.get(name.lower())
    if u is None:
        plan.append((name, "-", "skip-missing", "does not exist - skipped", None, None))
    else:
        plan.append((u["username"], u.get("role", "?"), "reset-password", "explicitly requested", None, u))

print(f"\n{'username':<18} {'role':<26} {'planned action':<16} note")
for r in plan:
    print(f"{r[0]:<18} {r[1]:<26} {r[2]:<16} {r[3]}")
counts = {}
for r in plan:
    counts[r[2]] = counts.get(r[2], 0) + 1
print("\nSummary: " + ", ".join(f"{v} {k}" for k, v in sorted(counts.items())))

if not APPLY:
    print("\nDRY RUN: nothing was changed. To apply, run:")
    print("  bash Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh --apply")
    sys.exit(0)

todo = [r for r in plan if r[2] in ("create", "reset-password")]
if not todo:
    print("\nNothing to do.")
    sys.exit(0)

# ---- test password ------------------------------------------------------
test_pass = os.environ.get("TEST_PASS")
if not test_pass:
    test_pass = getpass.getpass("New TEST password for the accounts (hidden, min 8 chars): ")
    if getpass.getpass("Repeat the test password: ") != test_pass:
        die("The two passwords do not match. Nothing was changed.")
if len(test_pass) < 8:
    die("The test password must be at least 8 characters. Nothing was changed.")

if any(r[2] == "reset-password" for r in todo):
    print("\nWARNING: the app's reset-password endpoint puts the new password in the URL,")
    print("so nginx access logs will contain it.")

# ---- apply --------------------------------------------------------------
done, failed = [], []
for username, role, action, _, acc, u in todo:
    if action == "create":
        body = dict(acc, password=test_pass)
        st, res = call("POST", "/admin/users", body, token)
        ok = st == 201
        label = "created"
    else:
        st, res = call("POST", f"/admin/users/{u['id']}/reset-password?newPassword={urllib.parse.quote(test_pass, safe='')}", token=token)
        ok = st in (200, 204)
        label = "password reset"
    if ok:
        done.append((username, role, label))
    else:
        failed.append((username, f"{action} failed, HTTP {st}: {res}"))

verified = 0
for username, role, label in done:
    st, _ = call("POST", "/auth/login", {"username": username, "password": test_pass})
    if st == 200:
        verified += 1
    else:
        failed.append((username, f"login check failed, HTTP {st}"))

print(f"\n{'username':<18} {'role':<26} result")
for row in done:
    print(f"{row[0]:<18} {row[1]:<26} {row[2]}")
print(f"\nCreated/reset: {len(done)}   Login verified: {verified}   Failed: {len(failed)}")
if failed:
    print("\nFAILED:")
    for f in failed:
        print("  ", f[0], "-", f[1])
    sys.exit(1)
print("All done.")
PY
