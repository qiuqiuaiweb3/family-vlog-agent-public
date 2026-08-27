# Read-only Execution Evidence API

This isolated Cloud Run service publishes a small, immutable JSON summary of the repository's frozen real-device execution evidence. It is a public verification surface for reviewers, not part of the Android Vlog runtime.

Deployed service: [`https://family-vlog-evidence-513339907677.us-central1.run.app`](https://family-vlog-evidence-513339907677.us-central1.run.app)

## Endpoints

- `GET /` — service descriptor.
- `GET /health` — health response.
- `GET /v1/evidence/latest` — aggregate evidence and provenance hashes.

The deployed artifact's only evidence payload is [`evidence-summary.json`](evidence-summary.json). It does not package the original activity log, prompts, model responses, inline media data, private device or account identifiers, local paths, private content URIs, or trace identifiers. It retains only public repository and immutable evidence links.

## Local verification

```bash
python3 -m venv .venv
.venv/bin/pip install -r cloud/evidence-api/requirements.txt
PYTHONPATH=cloud/evidence-api .venv/bin/python -m unittest cloud/evidence-api/test_main.py
```

Run the service locally:

```bash
cd cloud/evidence-api
PORT=8080 ../../.venv/bin/gunicorn --bind :8080 --workers 1 --threads 8 main:app
```

## Deploy

```bash
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  --project=familyvlog-a8a84

gcloud run deploy family-vlog-evidence \
  --source=cloud/evidence-api \
  --region=us-central1 \
  --project=familyvlog-a8a84 \
  --allow-unauthenticated \
  --min-instances=0 \
  --max-instances=1
```

The service deliberately accepts no submitted media or application payload and exposes no mutating route. Cloud Run can still produce ordinary platform request logs for endpoint access.
