package com.erezer.admin;

import org.springframework.boot.SpringApplication;

public class TestErezerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ErezerBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
