package com.github.j5ik2o.cqrs.es.kotlin.bootstrap

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Options
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan

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
            val commandLine = DefaultParser().parse(options, args)
            val profile =
                when {
                    commandLine.hasOption("w") -> {
                        "write"
                    }

                    commandLine.hasOption("r") -> {
                        error("read api is not implemented yet")
                    }

                    commandLine.hasOption("l") -> {
                        error("local rmu is not implemented yet")
                    }

                    else -> {
                        error("profile is not specified")
                    }
                }
            SpringApplication(Application::class.java)
                .apply { setAdditionalProfiles(profile) }
                .run(*args)
        }
    }
}
