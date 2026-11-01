package org.sand.ticwatch.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest

/** Needs a PostgreSQL (the docker-compose `db`), so it runs only when TICWATCH_DB_URL points at one. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TICWATCH_DB_URL", matches = ".+")
class TicWatchConnectorApplicationTests {

    @Test
    fun contextLoads() {
    }

}
