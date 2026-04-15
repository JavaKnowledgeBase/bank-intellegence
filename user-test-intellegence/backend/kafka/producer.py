class KafkaProducer:
    def publish_test_results(self, payload: dict) -> dict:
        return {"topic": "ctip.test-results", "status": "stubbed", "payload": payload}
