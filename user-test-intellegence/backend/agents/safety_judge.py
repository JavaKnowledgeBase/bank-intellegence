class SafetyJudgeAgent:
    def judge(self, text: str) -> dict:
        risky = any(token in text.lower() for token in ["password", "real pii", "ssn"])
        return {"accepted": not risky, "reason": "Rejected due to risky content" if risky else "Accepted"}
