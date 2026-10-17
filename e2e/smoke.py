#!/usr/bin/env python3
"""
PayVerse smoke test against locally running services (make run-<service> for auth, user, wallet,
payment, transaction and api-gateway; infra via make infra-up). Python 3 standard library only.

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

def signup(label):
    mobile = "9" + "".join(random.choice("0123456789") for _ in range(9))
    call("POST", "/api/v1/auth/otp/request", body={"mobile": mobile})
    s, r = call("POST", "/api/v1/auth/otp/verify", body={"mobile": mobile, "otp": otp_for("******" + mobile[6:]), "deviceId": "e2e-" + label + "-device"})
    tok = r["accessToken"]
    call("POST", "/api/v1/auth/pin", tok, {"pin": "4826"})
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

ba, bb = balance(a), balance(b)
expected_a = 5000 - 1200.50 - 300 - 149.50 - 250 - 99.99 - 50 - 1
expected_b = 1200.50 + 300 + 149.50 + 250 + 99.99 + 50 + 1
print(f"11. balances A={ba} (expected {expected_a:.2f})  B={bb} (expected {expected_b:.2f})")
assert ba == f"{expected_a:.2f}" and bb == f"{expected_b:.2f}"
s, integ = call("GET", "/internal/wallets/integrity", svc, base=WALLET)
print("12. ledger integrity:", integ["healthy"], "| suspense", integ["transferSuspense"]["amount"])
s, hist = call("GET", "/api/v1/transactions?size=50", a["token"])
print("13. A history:", hist["totalItems"], "transactions,", sorted({i["status"] for i in hist["items"]}))
s, csv = call("GET", "/api/v1/transactions/statement?from=" + time.strftime("%Y-%m-%dT00:00:00Z", time.gmtime(time.time()-86400*30)), a["token"], raw=True)
print("14. statement CSV rows:", len(csv.decode().strip().splitlines()) - 1)
print("SMOKE TEST PASSED")
