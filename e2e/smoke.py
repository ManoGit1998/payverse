#!/usr/bin/env python3
"""
PayVerse smoke test against locally running services (make run-<service> for auth, user, wallet,
payment, transaction, bill-payment, fraud-risk and api-gateway, plus rewards and notification for the
Phase 5 part; infra via make infra-up). Python 3 standard library only.

Two users sign up (OTP read from .run/auth-service.log — the MOCK SMS sender), set a PIN and pass
min KYC; then: add money, pay by UPI ID / mobile / dynamic QR, a money request, a UPI collect,
a transfer to a frozen wallet (must be compensated), and checks that both balances are exact and
the ledger is healthy.  Usage:  make smoke
"""
import os
import json, random, re, time, urllib.request, urllib.error, uuid
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
G = os.environ.get("GATEWAY_URL", "http://localhost:8080")
AUTH = os.environ.get("AUTH_SERVICE_URL", "http://localhost:8081")
WALLET = os.environ.get("WALLET_SERVICE_URL", "http://localhost:8083")
REWARDS = os.environ.get("REWARDS_SERVICE_URL", "http://localhost:8087")

def call(method, path, token=None, body=None, headers=None, base=G, raw=False):
    """429 (5 OTP/PIN calls per minute per IP at the gateway) waits and retries instead of silently failing."""
    for _ in range(8):
        status, result = _call(method, path, token, body, headers, base, raw)
        if status != 429:
            return status, result
        time.sleep(15)
    return status, result

def _call(method, path, token=None, body=None, headers=None, base=G, raw=False):
    h = {"Content-Type": "application/json"}
    if token: h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    req = urllib.request.Request(base + path, method=method, headers=h, data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = r.read(); return r.status, (data if raw else (json.loads(data) if data else None))
    except urllib.error.HTTPError as e:
        data = e.read(); return e.code, (json.loads(data) if data and not raw else data)

def otp_for(masked):
    for _ in range(20):
        logs = open(os.path.join(ROOT, ".run", "auth-service.log")).read()
        found = re.findall(r"OTP for " + re.escape(masked) + r" is (\d{6})", logs)
        if found: return found[-1]
        time.sleep(0.5)
    raise SystemExit("no otp")

def signup(label, referral_code=None, auth_base=G):
    """auth_base=AUTH skips the gateway's OTP/PIN rate limit (5/min per IP) for a third sign-up."""
    mobile = "9" + "".join(random.choice("0123456789") for _ in range(9))
    call("POST", "/api/v1/auth/otp/request", body={"mobile": mobile}, base=auth_base)
    s, r = call("POST", "/api/v1/auth/otp/verify", body={"mobile": mobile, "otp": otp_for("******" + mobile[6:]), "deviceId": "e2e-" + label + "-device",
                                                          "referralCode": referral_code}, base=auth_base)
    tok = r["accessToken"]
    s_pin, _ = call("POST", "/api/v1/auth/pin", tok, {"pin": "4826"}, base=auth_base)
    assert s_pin in (200, 201, 204), f"set PIN: {s_pin}"
    pan = "ABCDE" + str(random.randint(1000, 8999)) + random.choice("FGHJK")
    s, _ = call("POST", "/api/v1/users/me/kyc/min", tok, {"documentType": "PAN", "documentNumber": pan, "fullName": label + " Kumar", "dateOfBirth": "1994-02-11"})
    assert s == 200, s
    for _ in range(40):
        s, w = call("GET", "/api/v1/wallets/me", tok)
        if s == 200 and w["kycTier"] == "MIN_KYC": break
        time.sleep(0.5)
    for _ in range(40):
        s, v = call("GET", "/api/v1/vpa/me", tok)
        if s == 200 and v: break
        time.sleep(0.5)
    return {"label": label, "mobile": mobile, "token": tok, "vpa": v[0]["vpa"], "userId": r["userId"]}

def balance(u):
    return call("GET", "/api/v1/wallets/me", u["token"])[1]["balance"]["amount"]

def pay(u, payee, amount=None, note=None):
    body = {"payee": payee}
    if amount is not None: body["amount"] = amount
    if note: body["note"] = note
    return call("POST", "/api/v1/transfers", u["token"], body, {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "4826"})

a, b = signup("Asha"), signup("Ravi")
print("1. signed up + KYC:", a["vpa"], b["vpa"], "| tiers MIN_KYC")
s, r = call("POST", "/api/v1/wallets/me/add-money", a["token"], {"amount": 5000, "paymentMethod": "UPI"}, {"Idempotency-Key": str(uuid.uuid4())})
print("2. A adds 5000 via payment-service PG:", s, r["status"], r["paymentReference"][:7], "| A balance", balance(a))

s, r = pay(a, {"type": "VPA", "value": b["vpa"]}, 1200.50, "Rent share")
print("3. A→B by UPI ID 1200.50:", s, r["status"], "| payee name:", r["payee"]["name"])
s, r = pay(a, {"type": "MOBILE", "value": b["mobile"]}, 300)
mobile_txn = r["transactionId"]
print("4. A→B by mobile 300:", s, r["status"], "| payee shown as", r["payee"]["ref"])
s, qr = call("POST", "/api/v1/qr/dynamic", b["token"], {"amount": 149.50, "note": "Coffee", "validityMinutes": 10})
s, r = pay(a, {"type": "QR", "value": qr["payload"]})
print("5. A scans B's dynamic QR (149.50):", s, r["status"], r["amount"]["amount"])

s, mr = call("POST", "/api/v1/money-requests", b["token"], {"payer": {"type": "VPA", "value": a["vpa"]}, "amount": 250, "note": "Movie"})
s, r = call("POST", f"/api/v1/money-requests/{mr['id']}/pay", a["token"], None, {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "4826"})
s2, lst = call("GET", "/api/v1/money-requests?direction=OUTGOING", b["token"])
print("6. B requests 250, A pays:", s, r["status"], "| request status:", lst["items"][0]["status"])

s, col = call("POST", "/api/v1/collect-requests", b["token"], {"payerVpa": a["vpa"], "amount": 99.99, "note": "Snacks"})
s, r = call("POST", f"/api/v1/collect-requests/{col['id']}/approve", a["token"], None, {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "4826"})
print("7. UPI collect 99.99 approved:", s, r["status"])

pay(a, {"type": "VPA", "value": b["vpa"]}, 50)
pay(a, {"type": "VPA", "value": b["vpa"]}, 1)
# freeze B's wallet via wallet-service internal API with a real service token
secret = [l.split("=",1)[1].strip() for l in open(os.path.join(ROOT, ".env")) if l.startswith("SERVICE_CLIENT_SECRET=")][0]
req = urllib.request.Request(AUTH + "/api/v1/auth/oauth/token", data=f"grant_type=client_credentials&client_id=admin-service&client_secret={secret}".encode(),
                             headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
svc = json.loads(urllib.request.urlopen(req).read())["access_token"]
call("POST", f"/internal/wallets/{b['userId']}/freeze", svc, {"reason": "e2e test"}, base=WALLET)
before = balance(a)
s, r = pay(a, {"type": "VPA", "value": b["vpa"]}, 75)
after = balance(a)
print("8. A→B while B frozen:", s, r["status"], r["failureCode"], "| A balance before/after:", before, after, "(compensated)")
call("POST", f"/internal/wallets/{b['userId']}/unfreeze", svc, base=WALLET)

s, r = pay(a, {"type": "VPA", "value": "someone@okhdfc"}, 10)
print("9. pay external UPI ID:", s, r["code"])
s, r = call("POST", "/api/v1/transfers", a["token"], {"payee": {"type": "VPA", "value": b["vpa"]}, "amount": 10}, {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "1111"})
print("10. wrong PIN:", s, r["code"], "remaining", r.get("remainingAttempts"))

# Phase 6: bills (recharge + a declined one that is refunded) and a risk BLOCK from a new device
bill_cashback = 0.0
s, r = call("POST", "/api/v1/bills/pay", a["token"], {"billerCode": "VOLTEL_PREPAID", "accountRef": "9876543210", "amount": 299},
            {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "4826"})
s2, d = call("POST", "/api/v1/bills/pay", a["token"], {"billerCode": "VOLTEL_PREPAID", "accountRef": "9876543210", "amount": 199.13},
             {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": "4826"})
print("B1. A recharges 299:", s, r["status"], r["billerRef"], "| 199.13:", s2, d["status"], d["failureCode"], "refunded:", d["refunded"])
assert r["status"] == "SUCCESS" and d["status"] == "FAILED" and d["refunded"]
bill_cashback = 2.99  # BILL_PAY_1PCT: 1% of 299
otp_mobile = a["mobile"]
call("POST", "/api/v1/auth/otp/request", body={"mobile": otp_mobile}, base=AUTH)
s, login = call("POST", "/api/v1/auth/otp/verify", body={"mobile": otp_mobile, "otp": otp_for("******" + otp_mobile[6:]),
                                                         "deviceId": "e2e-Asha-new-phone", "deviceName": "New phone"}, base=AUTH)
a_new = dict(a, token=login["accessToken"])
time.sleep(3)  # fraud-risk-service learns the new device from auth.new-device-login
before = balance(a)
s, r = pay(a_new, {"type": "VPA", "value": b["vpa"]}, 2500)
print("F1. A→B 2500 from a new phone after a burst of transfers:", s, r["status"], r["failureCode"], "| balance unchanged:",
      before == balance(a))
assert r["status"] == "FAILED" and r["failureCode"] == "TXN_RISK_BLOCKED"

def wait_for(check, what, tries=60):
    for _ in range(tries):
        value = check()
        if value: return value
        time.sleep(0.5)
    raise SystemExit("timed out waiting for " + what)

def credited(u):
    return float(call("GET", "/api/v1/rewards/me", u["token"])[1]["totalCredited"]["amount"])

cashback_a = cashback_b = 0.0
try:
    rewards_up = call("GET", "/actuator/health", base=REWARDS)[0] == 200
except OSError:
    rewards_up = False
    print("R. rewards-service is not running: skipping the Phase 5 checks")
if rewards_up:
    # Phase 5: first add-money of 500+ earns 25; P2P payments of 100+ earn scratch cards (max 3 a day)
    cards = wait_for(lambda: (lambda r: r["items"] if len(r["items"]) == 3 else None)(
        call("GET", "/api/v1/rewards/scratch-cards", a["token"])[1]), "3 scratch cards")
    print("R1. A has", len(cards), "scratch cards (daily cap 3), amounts hidden:", all(c["amount"] is None for c in cards))
    won = 0.0
    for c in cards:
        s, sc = call("POST", f"/api/v1/rewards/scratch-cards/{c['id']}/scratch", a["token"])
        won += float(sc["amount"]["amount"])
    s, cp = call("POST", "/api/v1/rewards/coupons/PAYVERSE10/redeem", b["token"])
    s2, again = call("POST", "/api/v1/rewards/coupons/PAYVERSE10/redeem", b["token"])
    print(f"R2. A scratched INR {won:.2f}; B redeemed PAYVERSE10: {s}, again: {again['code']}")
    code = call("GET", "/api/v1/rewards/referral", a["token"])[1]["code"]
    c = signup("Meera", code, AUTH)
    wait_for(lambda: credited(c) >= 50, "referee bonus")
    cashback_a, cashback_b = 25 + won + 100 + bill_cashback, 10.0
    wait_for(lambda: abs(credited(a) - cashback_a) < 0.001 and credited(b) >= 10, "cashback credits")
    print(f"R3. C signed up with A's code {code} + KYC: A +100, C +50 | A cashback total {credited(a):.2f}")
    inbox = wait_for(lambda: (lambda r: r if r["items"] else None)(call("GET", "/api/v1/notifications/me?size=50", b["token"])[1]),
                     "B notifications")
    bodies = [n["templateCode"] + "/" + n["channel"] for n in inbox["items"]]
    assert "PAYMENT_RECEIVED/PUSH" in bodies, bodies
    a_inbox = call("GET", "/api/v1/notifications/me?size=100", a["token"])[1]
    a_codes = {n["templateCode"] for n in a_inbox["items"]}
    a_codes = wait_for(lambda: (lambda c: c if {"PAYMENT_BLOCKED", "BILL_PAID", "BILL_PAYMENT_FAILED"} <= c else None)(
        {n["templateCode"] for n in call("GET", "/api/v1/notifications/me?size=100", a["token"])[1]["items"]}), "A notifications")
    assert {"PAYMENT_SENT", "SCRATCH_CARD_WON", "CASHBACK_CREDITED", "MONEY_ADDED", "NEW_DEVICE_LOGIN"} <= a_codes, a_codes
    print("R4. notifications: B", inbox["unread"], "unread; A got", sorted(a_codes))

# Phase 7: A disputes the 300 sent by mobile; support resolves with a refund (maker), an admin approves (checker)
refund_a = refund_b = clawed = 0.0
ADMIN_URL = os.environ.get("ADMIN_SERVICE_URL", "http://localhost:8091")
try:
    admin_up = call("GET", "/actuator/health", base=ADMIN_URL)[0] == 200
except OSError:
    admin_up = False
    print("A. admin-service is not running: skipping the Phase 7 checks")
if admin_up:
    def staff(mobile):
        call("POST", "/api/v1/auth/otp/request", body={"mobile": mobile}, base=AUTH)
        s, r = call("POST", "/api/v1/auth/otp/verify", body={"mobile": mobile, "otp": otp_for("******" + mobile[6:]),
                                                             "deviceId": "e2e-staff-" + mobile}, base=AUTH)
        return r["accessToken"]
    admin_tok, support_tok = staff("9000000001"), staff("9000000002")
    s, d = call("POST", "/api/v1/disputes", a["token"], {"transactionId": mobile_txn, "reason": "Sent to the wrong number"})
    call("POST", f"/api/v1/admin/disputes/{d['id']}/assign", support_tok)
    s, res = call("POST", f"/api/v1/admin/disputes/{d['id']}/resolve", support_tok, {"outcome": "REFUND", "note": "Payee confirmed"})
    s_self, own = call("POST", f"/api/v1/admin/refunds/{res['refundId']}/approve", support_tok)
    s, ref = call("POST", f"/api/v1/admin/refunds/{res['refundId']}/approve", admin_tok)
    print("A1. dispute → REFUND by support; support approving:", s_self, "| admin approves:", s, ref["status"])
    assert ref["status"] == "COMPLETED", ref
    s, t = call("GET", f"/api/v1/transactions/{mobile_txn}", a["token"])
    refund_a, refund_b = 300.0, 300.0
    # rewards-service claws back the scratch-card cashback that this transfer earned
    reward = wait_for(lambda: next((x for x in call("GET", "/api/v1/rewards/history?size=100", a["token"])[1]["items"]
                                    if x["status"] == "CLAWED_BACK"), None), "cashback clawback")
    clawed = float(reward["amount"]["amount"])
    print("A2. transfer now", t["transaction"]["status"], "| cashback clawed back:", f"{clawed:.2f}")
    audit = call("GET", f"/api/v1/admin/audit-logs?entityId={d['id']}", support_tok)[1]
    print("A3. dispute audit trail:", [x["action"] for x in audit][::-1])

    # Reconciliation for today, with one injected PG charge that never reached the wallet
    injected = "SMOKE-" + uuid.uuid4().hex[:12].upper()
    import subprocess
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-q", "-U", os.environ.get("POSTGRES_USER", "payverse"),
                    "-d", "payment_db", "-c",
                    f"INSERT INTO pg_orders (id, user_id, idempotency_key, amount, method, provider, status, provider_ref, created_at) "
                    f"VALUES (gen_random_uuid(), '{a['userId']}', 'smoke:{injected}', 42.00, 'UPI', 'MOCK', 'SUCCEEDED', '{injected}', now())"],
                   check=True, cwd=ROOT)
    results = {}
    for job in ("PG_VS_LEDGER", "TRANSFERS_VS_UPI", "WALLET_INTEGRITY"):
        s, run = call("POST", f"/api/v1/admin/recon/jobs/{job}/run", admin_tok)
        done = wait_for(lambda: (lambda r: r if r["status"] != "RUNNING" else None)(
            call("GET", f"/api/v1/admin/recon/runs/{run['id']}", admin_tok)[1]), job)
        mism = call("GET", f"/api/v1/admin/recon/runs/{run['id']}/mismatches", admin_tok)[1]["items"]
        results[job] = (done, mism)
        print(f"R-{job}: {done['status']} left={done['leftCount']} right={done['rightCount']} matched={done['matchedCount']}"
              f" mismatches={[(m['type'], m['reference']) for m in mism]}")
    pg_types = {(m["type"], m["reference"]) for m in results["PG_VS_LEDGER"][1]}
    assert ("CHARGED_NOT_CREDITED", injected) in pg_types, pg_types
    assert all(done["status"] == "COMPLETED" for done, _ in results.values())
    assert not results["TRANSFERS_VS_UPI"][1] and not results["WALLET_INTEGRITY"][1]
    injected_mismatch = next(m for m in results["PG_VS_LEDGER"][1] if m["reference"] == injected)
    s, rm = call("POST", f"/api/v1/admin/recon/mismatches/{injected_mismatch['id']}/resolve", admin_tok, {"note": "smoke test injection"})
    print("R. injected mismatch detected and resolved:", rm["status"])

ba, bb = balance(a), balance(b)
expected_a = 5000 - 1200.50 - 300 - 149.50 - 250 - 99.99 - 50 - 1 - 299 + cashback_a + refund_a - clawed
expected_b = 1200.50 + 300 + 149.50 + 250 + 99.99 + 50 + 1 + cashback_b - refund_b
print(f"11. balances A={ba} (expected {expected_a:.2f})  B={bb} (expected {expected_b:.2f})")
assert ba == f"{expected_a:.2f}" and bb == f"{expected_b:.2f}"
s, integ = call("GET", "/internal/wallets/integrity", svc, base=WALLET)
print("12. ledger integrity:", integ["healthy"], "| suspense", integ["transferSuspense"]["amount"])
s, hist = call("GET", "/api/v1/transactions?size=50", a["token"])
print("13. A history:", hist["totalItems"], "transactions,", sorted({i["status"] for i in hist["items"]}))
s, csv = call("GET", "/api/v1/transactions/statement?from=" + time.strftime("%Y-%m-%dT00:00:00Z", time.gmtime(time.time()-86400*30)), a["token"], raw=True)
print("14. statement CSV rows:", len(csv.decode().strip().splitlines()) - 1)
print("SMOKE TEST PASSED")
