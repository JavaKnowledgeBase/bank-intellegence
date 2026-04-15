import process from "node:process";

const suiteRunId = process.env.SUITE_RUN_ID ?? "local-suite";
const shardNumber = Number(process.env.SHARD_NUMBER ?? "0");
const service = process.env.TARGET_SERVICE ?? "customer-agent-service";
const testCaseIds = (process.env.TEST_CASE_IDS ?? "tc-sample-001").split(",").filter(Boolean);
const shouldFail = service === "loan-prescreen-service" && shardNumber === 0;
const failed = shouldFail ? Math.min(1, testCaseIds.length) : 0;
const passed = Math.max(0, testCaseIds.length - failed);

const payload = {
  suite_run_id: suiteRunId,
  shard_number: shardNumber,
  passed,
  failed,
  errored: 0,
  duration_ms: 800 + testCaseIds.length * 75,
  test_case_ids: testCaseIds,
  failure_messages: shouldFail ? ["Eligibility rule mismatch on synthetic income band"] : [],
};

process.stdout.write(JSON.stringify(payload));
