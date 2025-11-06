---
applyTo: "**"
---
# how to run sbt commands when copilot is suggesting something
- prefer using metals MCP commands over sbt commands - always try them first especially for compiling, testing, running
- only use sbt when scala metals MCP does not support your task
- when using sbt use client mode like this `sbt --client "..."` notice the quotes - you will usually need them
- an exception in for code coverage - when testing code coverage use `sbt "clean; coverage; test; coverageReport"`
# coding style
- This project uses Scala 3
- Use braceless syntax in all code conforming to Scala 3 style guide
- This project uses ZIO - use idiomatic ZIO
- Use ZIO layers for services and dependency injection
- Use idiomatic zio-test for tests
- In tests remember to use for comprehensions to chain zio effects
- Code for performance - if a function is simple as does not need to be an effect - just make it a function.
- This library is an SQL client library - so in most tests we will use test container - see existing tests for examples
