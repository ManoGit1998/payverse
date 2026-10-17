#!/usr/bin/env python3
"""
Creates load-test users for k6: signs up N users (OTP + PIN through auth-service directly, so the
gateway's 5-per-minute OTP limit doesn't apply), passes min KYC, adds INR 5,000 each and writes
load-tests/.users.json (git-ignored) with their access tokens (valid 15 minutes) and UPI IDs.

    python3 load-tests/seed.py --users 20            # OTPs from the Docker auth container (default)
    OTP_SOURCE=file python3 load-tests/seed.py       # services run from source (make run-*)
"""
import argparse
import json
import os
import random
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "e2e"))
import e2e  # noqa: E402  (reuses the HTTP, OTP and wait helpers)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".users.json")


def create(i):
    mobile = "9" + "".join(random.choice("0123456789") for _ in range(9))
    e2e.call("POST", "/api/v1/auth/otp/request", body={"mobile": mobile}, base=e2e.AUTH)
    s, r = e2e.call("POST", "/api/v1/auth/otp/verify", body={"mobile": mobile, "otp": e2e.otp_for(mobile), "deviceId": f"k6-load-device-{i}"},
                    base=e2e.AUTH)
    e2e.expect(s == 200, f"verify {mobile}: {s}")
    token = r["accessToken"]
    e2e.call("POST", "/api/v1/auth/pin", token, {"pin": e2e.PIN}, base=e2e.AUTH)
    pan = "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ") for _ in range(5)) + str(random.randint(1000, 8999)) + random.choice("FGHJK")
    name = "Load Tester " + "".join(random.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(6)).capitalize()
    s, _ = e2e.call("POST", "/api/v1/users/me/kyc/min", token,
                    {"documentType": "PAN", "documentNumber": pan, "fullName": name, "dateOfBirth": "1990-01-01"})
    e2e.expect(s == 200, f"kyc {mobile}: {s}")
    e2e.wait_for(lambda: e2e.call("GET", "/api/v1/wallets/me", token)[1].get("kycTier") == "MIN_KYC", "wallet tier")
    s, _ = e2e.call("POST", "/api/v1/wallets/me/add-money", token, {"amount": 5000, "paymentMethod": "UPI"},
                    {"Idempotency-Key": str(uuid.uuid4())})
    e2e.expect(s == 201, f"add money {mobile}: {s}")
    vpa = e2e.wait_for(lambda: (lambda v: v[0]["vpa"] if v else None)(e2e.call("GET", "/api/v1/vpa/me", token)[1]), "UPI ID")
    return {"token": token, "vpa": vpa, "userId": r["userId"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, default=20)
    args = parser.parse_args()
    with ThreadPoolExecutor(max_workers=5) as pool:
        users = list(pool.map(create, range(args.users)))
    with open(OUT, "w") as f:
        json.dump({"pin": e2e.PIN, "users": users}, f)
    print(f"seeded {len(users)} users with INR 5,000 each → {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
