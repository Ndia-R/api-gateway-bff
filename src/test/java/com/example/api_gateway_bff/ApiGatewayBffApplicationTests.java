package com.example.api_gateway_bff;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebMvc
@TestPropertySource(properties = {
	"rate-limit.enabled=false"
})
class ApiGatewayBffApplicationTests {

	@Test
	void contextLoads() {
		// Basic context loading test
	}

}
