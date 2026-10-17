#!/usr/bin/env python3
"""
PayVerse end-to-end scenario (make e2e). Python 3 standard library only.

Two users sign up (MOCK OTP), set a PIN and pass minimum KYC; A adds money, pays B by UPI ID and pays
a mobile recharge; then the script verifies every balance three ways:
  1. the expected arithmetic (including cashback the rewards engine credited),
  2. each wallet's own ledger: the balance equals the sum of its ledger entries,
  3. wallet-service's integrity report (all wallets, all journals balanced, transfer suspense at zero).

OTPs are read from the auth-service log: the Docker container (OTP_SOURCE=docker, the default) or
.run/auth-service.log (OTP_SOURCE=file) when services run from source.
"""
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080")
AUTH = os.environ.get("AUTH_SERVICE_URL", "http://localhost:8081")
WALLET = os.environ.get("WALLET_SERVICE_URL", "http://localhost:8083")
OTP_SOURCE = os.environ.get("OTP_SOURCE", "docker")
PIN = "4826"


def call(method, path, token=None, body=None, headers=None, base=GATEWAY, form=None, retries=8):
    """One HTTP call. 429 (the gateway allows 5 OTP/PIN calls per minute per IP) waits for Retry-After and retries."""
    for _ in range(retries):
        status, body_or_error, retry_after = _call(method, path, token, body, headers, base, form)
        if status != 429:
            return status, body_or_error
        print(f"   (rate limited on {path}; waiting {retry_after}s)")
        time.sleep(retry_after)
    return status, body_or_error


def _call(method, path, token, body, headers, base, form):
    h = {"Content-Type": "application/json"}
    data = None if body is None else json.dumps(body).encode()
    if form is not None:
        h["Content-Type"] = "application/x-www-form-urlencoded"
        data = form.encode()
    if token:
        h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    req = urllib.request.Request(base + path, method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None, 0
    except urllib.error.HTTPError as e:
        raw = e.read()
        retry_after = int(e.headers.get("Retry-After") or 15)
        try:
            return e.code, json.loads(raw) if raw else None, retry_after
        except ValueError:
            return e.code, raw.decode(errors="replace"), retry_after


def expect(condition, message):
    if not condition:
        print("E2E FAILED:", message)
        sys.exit(1)


def wait_for(check, what, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = check()
        if value:
            return value
        time.sleep(0.5)
    expect(False, "timed out waiting for " + what)


def auth_log():
    if OTP_SOURCE == "file":
        with open(os.path.join(ROOT, ".run", "auth-service.log"), errors="replace") as f:
            return f.read()
    container = os.environ.get("AUTH_CONTAINER", "payverse-auth-service-1")
    result = subprocess.run(["docker", "logs", container], capture_output=True, text=True)
    return result.stdout + result.stderr


def otp_for(mobile):
    pattern = re.compile(r"OTP for " + re.escape("******" + mobile[6:]) + r" is (\d{6})")
    return wait_for(lambda: (pattern.findall(auth_log()) or [None])[-1], "the OTP for " + mobile)


def money(value):
    return Decimal(str(value)).quantize(Decimal("0.01"))


def signup(label):
    mobile = "9" + "".join(random.choice("0123456789") for _ in range(9))
    s, _ = call("POST", "/api/v1/auth/otp/request", body={"mobile": mobile})
    expect(s == 202 or s == 200, f"OTP request for {label}: {s}")
    s, r = call("POST", "/api/v1/auth/otp/verify", body={"mobile": mobile, "otp": otp_for(mobile), "deviceId": f"e2e-{label}"})
    expect(s == 200 and r.get("accessToken"), f"OTP verify for {label}: {s} {r}")
    token = r["accessToken"]
    s, _ = call("POST", "/api/v1/auth/pin", token, {"pin": PIN})
    expect(s in (200, 201, 204), f"set PIN for {label}: {s}")
    pan = "ABCDE" + str(random.randint(1000, 8999)) + random.choice("FGHJK")
    s, _ = call("POST", "/api/v1/users/me/kyc/min", token,
                {"documentType": "PAN", "documentNumber": pan, "fullName": label + " Sharma", "dateOfBirth": "1993-04-12"})
    expect(s == 200, f"min KYC for {label}: {s}")
    wait_for(lambda: call("GET", "/api/v1/wallets/me", token)[1].get("kycTier") == "MIN_KYC", f"{label}'s wallet tier")
    vpa = wait_for(lambda: (lambda r: r[0]["vpa"] if r else None)(call("GET", "/api/v1/vpa/me", token)[1]), f"{label}'s UPI ID")
    return {"label": label, "token": token, "vpa": vpa, "userId": r["userId"]}


def balance(user):
    return money(call("GET", "/api/v1/wallets/me", user["token"])[1]["balance"]["amount"])


def ledger_sum(user):
    """Credits minus debits over the wallet's whole ledger history (paged)."""
    total, page = Decimal("0"), 0
    while True:
        s, r = call("GET", f"/api/v1/wallets/me/ledger?page={page}&size=100", user["token"])
        expect(s == 200, f"ledger of {user['label']}: {s}")
        for e in r["items"]:
            amount = money(e["amount"]["amount"])
            total += amount if e["side"] == "CREDIT" else -amount
        page += 1
        if page >= r["totalPages"]:
            return total


def credited_cashback(user):
    s, r = call("GET", "/api/v1/rewards/me", user["token"])
    return money(r["totalCredited"]["amount"]) if s == 200 else Decimal("0")


def service_token():
    secret = os.environ.get("SERVICE_CLIENT_SECRET")
    if not secret and os.path.exists(os.path.join(ROOT, ".env")):
        for line in open(os.path.join(ROOT, ".env")):
            if line.startswith("SERVICE_CLIENT_SECRET="):
                secret = line.split("=", 1)[1].strip()
    s, r = call("POST", "/api/v1/auth/oauth/token", base=AUTH,
                form=f"grant_type=client_credentials&client_id=reconciliation-service&client_secret={secret}")
    expect(s == 200, f"service token: {s}")
    return r["access_token"]


def main():
    print(f"PayVerse e2e against {GATEWAY} (OTP from {OTP_SOURCE})")
    a, b = signup("Asha"), signup("Ravi")
    print(f"1. two users signed up with PIN + min KYC: {a['vpa']}, {b['vpa']}")

    s, r = call("POST", "/api/v1/wallets/me/add-money", a["token"], {"amount": 2000, "paymentMethod": "UPI"},
                {"Idempotency-Key": str(uuid.uuid4())})
    expect(s == 201 and r["status"] == "SUCCESS", f"add money: {s} {r}")
    print("2. A added INR 2000.00:", r["paymentReference"])

    key = str(uuid.uuid4())
    transfer = {"payee": {"type": "VPA", "value": b["vpa"]}, "amount": 500, "note": "e2e transfer"}
    s, r = call("POST", "/api/v1/transfers", a["token"], transfer, {"Idempotency-Key": key, "X-Txn-Pin": PIN})
    expect(s == 201 and r["status"] == "SUCCESS", f"transfer: {s} {r}")
    s2, replay = call("POST", "/api/v1/transfers", a["token"], transfer, {"Idempotency-Key": key, "X-Txn-Pin": PIN})
    expect(s2 == 200 and replay["transactionId"] == r["transactionId"], f"idempotent replay: {s2}")
    print("3. A paid B INR 500.00 by UPI ID:", r["status"], "| retry with the same Idempotency-Key replayed it")

    s, bill = call("POST", "/api/v1/bills/pay", a["token"], {"billerCode": "VOLTEL_PREPAID", "accountRef": "9876543210", "amount": 249},
                   {"Idempotency-Key": str(uuid.uuid4()), "X-Txn-Pin": PIN})
    expect(s == 201 and bill["status"] == "SUCCESS", f"bill payment: {s} {bill}")
    print("4. A paid a mobile recharge of INR 249.00:", bill["billerRef"])

    # rewards are asynchronous: first add-money of 500+ earns 25, the recharge earns 1% (2.49)
    cashback = wait_for(lambda: (lambda c: c if c >= money("27.49") else None)(credited_cashback(a)), "A's cashback", timeout=90)
    expected_a = money(2000) - money(500) - money(249) + cashback
    expected_b = money(500)
    ba, bb = balance(a), balance(b)
    print(f"5. balances: A {ba} (expected {expected_a}, cashback {cashback}), B {bb} (expected {expected_b})")
    expect(ba == expected_a and bb == expected_b, "balances do not match the expected arithmetic")

    la, lb = ledger_sum(a), ledger_sum(b)
    print(f"6. ledger: A's entries sum to {la}, B's to {lb}")
    expect(la == ba and lb == bb, "a wallet balance differs from the sum of its ledger entries")

    s, report = call("GET", "/internal/wallets/integrity", service_token(), base=WALLET)
    expect(s == 200, f"integrity report: {s}")
    print(f"7. ledger integrity: healthy={report['healthy']}, transfer suspense {report['transferSuspense']['amount']}")
    expect(report["healthy"] and money(report["transferSuspense"]["amount"]) == 0, f"ledger integrity: {report}")
    print("E2E PASSED")


if __name__ == "__main__":
    main()
