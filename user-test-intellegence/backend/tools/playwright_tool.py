class PlaywrightTool:
    def command_for_suite(self, suite_run_id: str, shard_number: int) -> list[str]:
        return ["node", "/runner/run-shard.js", suite_run_id, str(shard_number)]
