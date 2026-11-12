package com.alimtalk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlimtalkApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlimtalkApplication.class, args);
	}

}
