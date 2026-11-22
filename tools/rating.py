"""Builds ratings.json from the health reports in Firestore.

Every box reports at most every 12 hours per server whether the server worked there
(health/{server}/reports/{anonymous uid}). Rating of a server = share of the boxes that
reported it as working during the last DAYS days, 0..10, plus how many boxes reported.

Auth: service account JSON (read-only) in env FIREBASE_SA, or an OAuth token in env FIRESTORE_TOKEN
(local test: FIRESTORE_TOKEN=$(gcloud auth print-access-token) python tools/rating.py).
Writes ratings.json to the path given as the first argument (default: ratings.json).
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

PROJECT = "jaccollector-e7a403"
DAYS = 7


def token() -> str:
    if os.environ.get("FIRESTORE_TOKEN"):
        return os.environ["FIRESTORE_TOKEN"]
    from google.oauth2 import service_account
    import google.auth.transport.requests
    creds = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["FIREBASE_SA"]),
        scopes=["https://www.googleapis.com/auth/datastore"])
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token


def reports(tok: str):
    url = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents:runQuery"
    body = json.dumps({"structuredQuery": {"from": [{"collectionId": "reports", "allDescendants": True}]}})
    req = urllib.request.Request(url, data=body.encode(), method="POST", headers={
        "Authorization": "Bearer " + tok, "Content-Type": "application/json",
        "x-goog-user-project": PROJECT})
    with urllib.request.urlopen(req, timeout=60) as r:
        for row in json.load(r):
            d = row.get("document")
            if d:
                yield d


def main() -> None:
    out_path = sys.argv[1] if len(sys.argv) > 1 else "ratings.json"
    since = datetime.now(timezone.utc) - timedelta(days=DAYS)
    stats: dict[str, dict[str, int]] = {}
    for d in reports(token()):
        f = d.get("fields", {})
        url = f.get("url", {}).get("stringValue", "").lower().rstrip("/")
        at = f.get("at", {}).get("timestampValue")
        ok = f.get("ok", {}).get("booleanValue")
        if not url or at is None or ok is None:
            continue
        if datetime.fromisoformat(at.replace("Z", "+00:00")) < since:
            continue
        s = stats.setdefault(url, {"ok": 0, "fail": 0})
        s["ok" if ok else "fail"] += 1  # one document per box, so these are distinct boxes
    servers = {}
    for url, s in sorted(stats.items()):
        boxes = s["ok"] + s["fail"]
        servers[url] = {"score": round(10 * s["ok"] / boxes), "boxes": boxes, "ok": s["ok"], "fail": s["fail"]}
    result = {"updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "days": DAYS, "servers": servers}
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
