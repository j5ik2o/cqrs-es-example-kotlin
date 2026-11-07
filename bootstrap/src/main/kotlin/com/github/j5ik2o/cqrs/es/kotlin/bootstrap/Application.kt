package com.github.j5ik2o.cqrs.es.kotlin.bootstrap

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import kotlin.system.exitProcess

@SpringBootApplication
@ComponentScan(basePackages = ["com.github.j5ik2o.cqrs.es.kotlin"])
class Application {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options =
                Options().apply {
                    addOption("w", "write-api", false, "write api")
                    addOption("r", "read-api", false, "read api")
                    addOption("l", "local-rmu", false, "local rmu")
                }
            val commandLine =
                try {
                    // Spring Boot options such as --server.port=... must pass through unchanged.
                    DefaultParser().parse(options, args, true)
                } catch (e: ParseException) {
                    System.err.println(e.message)
                    exitProcess(1)
                }
            val profile =
                when {
                    commandLine.hasOption("w") -> {
                        "write"
                    }

                    commandLine.hasOption("r") -> {
                        "read"
                    }

                    commandLine.hasOption("l") -> {
                        "local-rmu"
                    }

                    else -> {
                        System.err.println(
                            "profile is not specified. Use -w, -r, or -l.",
                        )
                        exitProcess(1)
                    }
                }
            SpringApplication(Application::class.java)
                .apply { setAdditionalProfiles(profile) }
                .run(*args)
        }
    }
}
