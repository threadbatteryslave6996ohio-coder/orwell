#!/usr/bin/env python3
"""A stand-in for api.apify.com, so insta can be run end to end without an Apify account.

    python3 apps/insta/scripts/fake-apify.py &
    APIFY_TOKEN=anything APIFY_BASE_URL=http://127.0.0.1:9401 \
        java -jar apps/insta/target/insta-0.1.0-SNAPSHOT-exec.jar followers nasa

It serves canned data on both run paths the program uses: the synchronous one behind `profile`,
and the asynchronous start / poll / read-dataset / read-OUTPUT sequence behind `followers` and
`following`. It ignores the token entirely and never charges anybody.

This is a development fixture, not a test double — the tests have their own stub in
`src/test/java/dev/orwell/insta/support/ApifyStubServer.java` and do not use this file. Change one
and you are not changing the other.

Options:
  --port N        listen on N (default 9401)
  --pages N       hand out a continuation token for the first N-1 list pages, so `--all` has
                  something to walk (default 1, meaning a single page and no cursor)
"""

import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PROFILE = [{
    "id": "528817151", "username": "nasa", "fullName": "NASA",
    "biography": "Explore the universe and discover our home planet.",
    "followersCount": 97000000, "followsCount": 78, "postsCount": 3900,
    "verified": True, "private": False,
    "profilePicUrl": "https://example.invalid/nasa.jpg",
}]

FOLLOWERS = [
    {"id": "1", "username": "alice", "full_name": "Alice Adams", "is_verified": True,
     "is_private": False},
    {"id": "2", "username": "bob", "full_name": "Bob Brown", "is_verified": False,
     "is_private": True},
    {"id": "3", "username": "carol", "is_verified": False},
]

RUN = {"data": {
    "id": "run-1", "status": "SUCCEEDED",
    "defaultDatasetId": "dataset-1", "defaultKeyValueStoreId": "kvs-1",
}}


class Handler(BaseHTTPRequestHandler):
    pages_served = 0

    def log_message(self, *args):
        pass

    def _reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        path = self.path.split("?")[0]
        if path.endswith("/run-sync-get-dataset-items"):
            self._reply(200, PROFILE)          # the `profile` path: dataset, one call
        elif path.endswith("/runs"):
            Handler.pages_served += 1
            self._reply(201, RUN)              # the list path: start the run
        else:
            self._reply(200, RUN)              # /abort and anything else

    def do_GET(self):
        path = self.path.split("?")[0]
        if path.endswith("/items"):
            self._reply(200, FOLLOWERS)
        elif path.endswith("/records/OUTPUT"):
            # A token only while pages remain; its absence is how the walk ends.
            if Handler.pages_served < self.server.pages:
                self._reply(200, {"continuations": [
                    {"account": "nasa", "nextContinuationToken":
                        f"TOKEN-{Handler.pages_served + 1}"}]})
            else:
                self._reply(404, {"error": {"type": "record-not-found"}})
        else:
            self._reply(200, RUN)              # polling a run


def main():
    parser = argparse.ArgumentParser(description="Fake Apify API for running insta without a key.")
    parser.add_argument("--port", type=int, default=9401)
    parser.add_argument("--pages", type=int, default=1)
    options = parser.parse_args()

    server = HTTPServer(("127.0.0.1", options.port), Handler)
    server.pages = options.pages
    print(f"fake Apify on http://127.0.0.1:{options.port} "
          f"({options.pages} page(s) of list results)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
