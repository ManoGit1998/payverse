#!/usr/bin/env python3
"""
PayVerse smoke test against locally running services (make run-<service> for auth, user, wallet,
payment, transaction and api-gateway, plus rewards and notification for the Phase 5 part; infra via
make infra-up). Python 3 standard library only.

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
    call("POST", "/api/v1/auth/pin", tok, {"pin": "4826"}, base=auth_base)
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
    cashback_a, cashback_b = 25 + won + 100, 10.0
    wait_for(lambda: abs(credited(a) - cashback_a) < 0.001 and credited(b) >= 10, "cashback credits")
    print(f"R3. C signed up with A's code {code} + KYC: A +100, C +50 | A cashback total {credited(a):.2f}")
    inbox = wait_for(lambda: (lambda r: r if r["items"] else None)(call("GET", "/api/v1/notifications/me?size=50", b["token"])[1]),
                     "B notifications")
    bodies = [n["templateCode"] + "/" + n["channel"] for n in inbox["items"]]
    assert "PAYMENT_RECEIVED/PUSH" in bodies, bodies
    a_inbox = call("GET", "/api/v1/notifications/me?size=100", a["token"])[1]
    a_codes = {n["templateCode"] for n in a_inbox["items"]}
    assert {"PAYMENT_SENT", "SCRATCH_CARD_WON", "CASHBACK_CREDITED", "MONEY_ADDED"} <= a_codes, a_codes
    print("R4. notifications: B", inbox["unread"], "unread; A got", sorted(a_codes))

ba, bb = balance(a), balance(b)
expected_a = 5000 - 1200.50 - 300 - 149.50 - 250 - 99.99 - 50 - 1 + cashback_a
expected_b = 1200.50 + 300 + 149.50 + 250 + 99.99 + 50 + 1 + cashback_b
print(f"11. balances A={ba} (expected {expected_a:.2f})  B={bb} (expected {expected_b:.2f})")
assert ba == f"{expected_a:.2f}" and bb == f"{expected_b:.2f}"
s, integ = call("GET", "/internal/wallets/integrity", svc, base=WALLET)
print("12. ledger integrity:", integ["healthy"], "| suspense", integ["transferSuspense"]["amount"])
s, hist = call("GET", "/api/v1/transactions?size=50", a["token"])
print("13. A history:", hist["totalItems"], "transactions,", sorted({i["status"] for i in hist["items"]}))
s, csv = call("GET", "/api/v1/transactions/statement?from=" + time.strftime("%Y-%m-%dT00:00:00Z", time.gmtime(time.time()-86400*30)), a["token"], raw=True)
print("14. statement CSV rows:", len(csv.decode().strip().splitlines()) - 1)
print("SMOKE TEST PASSED")
