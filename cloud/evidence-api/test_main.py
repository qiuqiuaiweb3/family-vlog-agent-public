import unittest

import main


class EvidenceApiTest(unittest.TestCase):
    def setUp(self):
        self.client = main.app.test_client()

    def test_index_exposes_read_only_service_routes(self):
        response = self.client.get("/")

        self.assertEqual(200, response.status_code)
        self.assertEqual("/v1/evidence/latest", response.get_json()["latest_evidence"])
        self.assertEqual("nosniff", response.headers["X-Content-Type-Options"])

    def test_health_reports_ok(self):
        response = self.client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "ok"}, response.get_json())

    def test_latest_evidence_contains_only_aggregate_contract(self):
        response = self.client.get("/v1/evidence/latest")
        body = response.get_json()

        self.assertEqual(200, response.status_code)
        self.assertEqual(7, body["verified_run"]["total_model_call_count"])
        self.assertEqual(5, body["verified_run"]["selected_event_count"])
        self.assertEqual(
            {
                "contains_media_bytes": False,
                "contains_prompts_or_responses": False,
                "contains_inline_data": False,
                "contains_private_device_or_account_identifiers": False,
                "contains_local_paths_or_private_content_uris": False,
                "contains_trace_or_span_identifiers": False,
            },
            body["privacy"],
        )

    def test_mutating_methods_are_not_exposed(self):
        self.assertEqual(405, self.client.post("/v1/evidence/latest").status_code)


if __name__ == "__main__":
    unittest.main()
