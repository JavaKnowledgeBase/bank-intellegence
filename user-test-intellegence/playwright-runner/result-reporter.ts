export async function reportResult(payload) {
  return {
    endpoint: process.env.RESULT_CALLBACK_URL ?? "http://localhost:8091/api/v1/internal/shard-result",
    payload,
  };
}
