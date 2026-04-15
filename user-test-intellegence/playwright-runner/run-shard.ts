const suiteRunId = process.env.SUITE_RUN_ID ?? "local-suite";
const shardNumber = process.env.SHARD_NUMBER ?? "0";
const testCaseIds = (process.env.TEST_CASE_IDS ?? "tc-sample-001").split(",");

console.log(JSON.stringify({
  suiteRunId,
  shardNumber,
  message: "Simulated Playwright shard execution",
  testCaseIds,
}));
