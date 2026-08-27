import json
from pathlib import Path

from flask import Flask, jsonify


SUMMARY_PATH = Path(__file__).with_name("evidence-summary.json")


def load_summary() -> dict:
    with SUMMARY_PATH.open(encoding="utf-8") as summary_file:
        return json.load(summary_file)


app = Flask(__name__)
EVIDENCE_SUMMARY = load_summary()


@app.after_request
def add_response_headers(response):
    response.headers["Cache-Control"] = "public, max-age=300"
    response.headers["X-Content-Type-Options"] = "nosniff"
    return response


@app.get("/")
def index():
    return jsonify(
        service="Family Vlog Agent Execution Evidence API",
        description="Read-only aggregate evidence from one frozen real-device run.",
        latest_evidence="/v1/evidence/latest",
        health="/health",
        source_repository="https://github.com/qiuqiuaiweb3/family-vlog-agent-public",
    )


@app.get("/health")
def health():
    return jsonify(status="ok")


@app.get("/v1/evidence/latest")
def latest_evidence():
    return jsonify(EVIDENCE_SUMMARY)
