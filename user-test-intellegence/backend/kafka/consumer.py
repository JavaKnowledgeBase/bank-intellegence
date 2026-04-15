class KafkaConsumer:
    def subscriptions(self) -> list[str]:
        return ["cibap.deployments", "cibap.config-changes"]
