class K8sJobTool:
    def build_job_name(self, suite_run_id: str, shard_number: int) -> str:
        return f"ctip-exec-{suite_run_id[:8]}-shard-{shard_number}"
