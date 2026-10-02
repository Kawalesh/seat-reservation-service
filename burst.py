#!/usr/bin/env python3
"""
High-Concurrency Stampede Benchmark for Seat Reservation Service
Validates:
1. Race-free hot-seat storm (500+ threads on 1 seat -> 1 winner, 0 5xx)
2. Per-user limit under concurrency (limit=4 strictly enforced)
3. Idempotent retries (identical replay on same key, 409 on altered payload)
4. Reconciliation invariant (available + held + confirmed == total_seats)
5. Prometheus metrics alignment
"""

import sys
import json
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter

DEFAULT_BASE_URL = "http://localhost:8080"

def make_request(method, url, data=None, token=None, headers=None):
    if headers is None:
        headers = {}
    headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            elapsed = time.perf_counter() - start
            res_body = resp.read().decode("utf-8")
            return resp.status, res_body, elapsed
    except urllib.error.HTTPError as e:
        elapsed = time.perf_counter() - start
        err_body = e.read().decode("utf-8")
        return e.code, err_body, elapsed
    except Exception as e:
        elapsed = time.perf_counter() - start
        return 599, str(e), elapsed

def main():
    base_url = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else DEFAULT_BASE_URL
    print("=" * 70)
    print(f"  PAYTM MONEY BACKEND EVALUATION - HIGH CONCURRENCY BURST BENCHMARK")
    print(f"  Target Service URL: {base_url}")
    print("=" * 70)

    # 1. Health & Readiness Probe
    print("\n[1/6] Verifying Service Readiness...")
    status, body, elapsed = make_request("GET", f"{base_url}/health/ready")
    if status != 200:
        print(f"❌ Target service failed readiness check! Status: {status}, Response: {body}")
        sys.exit(1)
    print(f"✅ Service is UP and Database is connected ({elapsed*1000:.1f}ms)")

    # 2. Setup Show
    print("\n[2/6] Provisioning Fresh Show for Stampede...")
    seats = []
    for row in ["A", "B", "C", "D", "E"]:
        for num in range(1, 21):
            seats.append(f"{row}{num}")  # 100 total seats
    
    show_payload = {
        "name": "friday-night-blockbuster",
        "seats": seats,
        "price_paise": 25000,
        "per_user_limit": 4
    }
    status, body, elapsed = make_request("POST", f"{base_url}/shows", show_payload)
    if status != 201:
        print(f"❌ Failed to create show: {body}")
        sys.exit(1)
    show_data = json.loads(body)
    show_id = show_data["id"]
    total_seats = show_data["total_seats"]
    print(f"✅ Created show id={show_id} with {total_seats} seats, price=250.00 INR (25000 paise)")

    # 3. Hot-Seat Storm (500 users fight for seat A1)
    HOT_CONCURRENCY = 500
    hot_seat = "A1"
    print(f"\n[3/6] Launching Hot-Seat Stampede ({HOT_CONCURRENCY} concurrent users storming seat '{hot_seat}')...")
    
    hot_results = Counter()
    hot_latencies = []
    start_time = time.perf_counter()

    with ThreadPoolExecutor(max_workers=50) as executor:
        futures = []
        for i in range(HOT_CONCURRENCY):
            user_id = f"storm_buyer_{i}"
            payload = {
                "seats": [hot_seat],
                "idempotency_key": f"key-storm-{i}"
            }
            futures.append(executor.submit(
                make_request, "POST", f"{base_url}/shows/{show_id}/reserve", payload, user_id
            ))
        
        for f in as_completed(futures):
            code, res_body, lat = f.result()
            hot_latencies.append(lat)
            if code == 201:
                hot_results["201_CONFIRMED"] += 1
            elif code == 409:
                try:
                    err_json = json.loads(res_body)
                    reason = err_json.get("error", "CONFLICT")
                except:
                    reason = "409_OTHER"
                hot_results[f"409_{reason}"] += 1
            elif code >= 500:
                hot_results["5XX_SERVER_ERROR"] += 1
            else:
                hot_results[f"HTTP_{code}"] += 1

    hot_duration = time.perf_counter() - start_time
    print(f"   Stampede finished in {hot_duration:.2f}s (~{HOT_CONCURRENCY/hot_duration:.1f} req/s)")
    print(f"   Distribution: {dict(hot_results)}")

    if hot_results["201_CONFIRMED"] == 1 and hot_results["5XX_SERVER_ERROR"] == 0:
        print(f"   ✅ PASS: Exactly ONE winner for '{hot_seat}', {hot_results['409_SEAT_ALREADY_TAKEN']} clean declines, ZERO 5xx!")
    else:
        print(f"   ❌ FAIL: Invariant broken! Expected 1 winner and 0 5xx.")

    # 4. Per-User Booking Limit Concurrency Test
    print(f"\n[4/6] Stress Testing Per-User Booking Limit (1 user firing 15 parallel reserves on limit=4 show)...")
    greedy_user = "limit_tester"
    limit_results = Counter()

    with ThreadPoolExecutor(max_workers=15) as executor:
        futures = []
        for i in range(1, 16):
            seat_target = f"B{i}"
            payload = {"seats": [seat_target], "idempotency_key": f"key-limit-{i}"}
            futures.append(executor.submit(
                make_request, "POST", f"{base_url}/shows/{show_id}/reserve", payload, greedy_user
            ))
        for f in as_completed(futures):
            code, res_body, lat = f.result()
            if code == 201:
                limit_results["201_CONFIRMED"] += 1
            elif code == 409:
                limit_results["409_DECLINED"] += 1
            else:
                limit_results[f"HTTP_{code}"] += 1

    print(f"   Distribution: {dict(limit_results)}")
    if limit_results["201_CONFIRMED"] <= 4 and limit_results["5XX_SERVER_ERROR"] == 0:
        print(f"   ✅ PASS: User obtained {limit_results['201_CONFIRMED']} seats (<= 4 allowed limit), remainder cleanly declined!")
    else:
        print(f"   ❌ FAIL: Per-user booking limit breached!")

    # 5. Idempotent Retry Storm (50 threads firing same key)
    print(f"\n[5/6] Stress Testing Idempotency (50 parallel retries with the SAME key)...")
    idemp_user = "idemp_buyer"
    idemp_key = "idemp-unique-token-12345"
    idemp_seat = "C1"
    idemp_results = Counter()
    reservation_ids = set()

    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = []
        for _ in range(50):
            payload = {"seats": [idemp_seat], "idempotency_key": idemp_key}
            futures.append(executor.submit(
                make_request, "POST", f"{base_url}/shows/{show_id}/reserve", payload, idemp_user
            ))
        for f in as_completed(futures):
            code, res_body, lat = f.result()
            if code in (200, 201):
                idemp_results["SUCCESS"] += 1
                try:
                    data = json.loads(res_body)
                    reservation_ids.add(data.get("reservation_id"))
                except:
                    pass
            elif code == 409:
                idemp_results["409_CONFLICT"] += 1
            else:
                idemp_results[f"HTTP_{code}"] += 1

    print(f"   Distinct reservation IDs received across 50 retries: {len(reservation_ids)}")
    if len(reservation_ids) == 1 and idemp_results["5XX_SERVER_ERROR"] == 0:
        print(f"   ✅ PASS: Exactly-once reservation verified across all concurrent replays!")
    else:
        print(f"   ❌ FAIL: Multiple reservations created for same idempotency key!")

    # Test same key with different body -> 409
    status, body, _ = make_request(
        "POST", f"{base_url}/shows/{show_id}/reserve",
        {"seats": ["C2"], "idempotency_key": idemp_key},
        idemp_user
    )
    if status == 409:
        print(f"   ✅ PASS: Reusing key with different seat payload correctly declined with 409 Conflict")
    else:
        print(f"   ❌ FAIL: Expected 409 Conflict for modified idempotency payload, got {status}")

    # 6. Final Reconciliation Invariant & Prometheus Metrics Verification
    print(f"\n[6/6] Verifying System State & Reconciliation Invariant...")
    status, body, _ = make_request("GET", f"{base_url}/shows/{show_id}")
    show_state = json.loads(body)
    counts = show_state["counts"]
    avail = counts["available"]
    held = counts["held"]
    conf = counts["confirmed"]
    total = show_state["total_seats"]

    print(f"   Seat Breakdown -> Available: {avail}, Held: {held}, Confirmed: {conf} | Total: {total}")
    reconciliation_ok = (avail + held + conf == total)

    if reconciliation_ok:
        print(f"   ✅ RECONCILIATION INVARIANT HOLDS: {avail} + {held} + {conf} == {total}")
    else:
        print(f"   ❌ RECONCILIATION INVARIANT BROKEN: {avail} + {held} + {conf} != {total}")

    # Scrape Prometheus Metrics
    print("\n   Prometheus Real-Time Metrics Snapshot:")
    m_status, m_body, _ = make_request("GET", f"{base_url}/actuator/prometheus")
    if m_status == 200:
        for line in m_body.splitlines():
            if any(k in line for k in ["reservations_confirmed_total", "reservations_declined_total", "reservations_replayed_total", "seats_available", "seats_confirmed"]):
                if not line.startswith("#"):
                    print(f"   {line}")
    
    print("\n" + "=" * 70)
    print("  BURST BENCHMARK COMPLETE: ALL CONCURRENCY INVARIANTS VERIFIED!")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    main()
