class WebScoutAgent:
    allowlisted_domains = ["owasp.org", "martinfowler.com", "playwright.dev", "docs.github.com"]

    def policy(self) -> dict:
        return {
            "daily_cap": 5,
            "allowlisted_domains": self.allowlisted_domains,
            "safety_layers": 7,
        }
