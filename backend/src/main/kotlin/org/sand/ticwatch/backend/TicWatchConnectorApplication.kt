package org.sand.ticwatch.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TicWatchConnectorApplication

fun main(args: Array<String>) {
    runApplication<TicWatchConnectorApplication>(*args)
}
