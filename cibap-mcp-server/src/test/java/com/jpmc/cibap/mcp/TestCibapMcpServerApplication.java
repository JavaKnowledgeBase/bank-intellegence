package com.jpmc.cibap.mcp;

import org.springframework.boot.SpringApplication;

public class TestCibapMcpServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(CibapMcpServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
